package com.knowyourinterview.api.functional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-BROWSE — the public catalogue: filtering, searching, paging, sorting, and what a teaser is
 * allowed to reveal. See {@code docs/09-test-plan.md} §7.7.
 *
 * <p>This suite exists mostly because {@code ExperienceRepository#browsePublished} is mock-proof.
 * It's a hand-written JPQL query whose nullable filters each need an explicit
 * {@code CAST(:param AS string)} to plan at all on Postgres — a detail no mocked repository can
 * catch, and one that fails at runtime rather than at compile time. Everything here runs against
 * a real Postgres planner.
 *
 * <p>Filter values are deliberately single words. Query strings are handed to
 * {@code TestRestTemplate} as URI templates, and pre-encoded characters in a template are not
 * reliably left alone — sidestepping that keeps these tests about the query, not about encoding.
 */
class BrowseFunctionalIT extends FunctionalTestBase {

    private JSONObject browse(String query) {
        ResponseEntity<String> response = getAnonymously("/api/v1/experiences" + query);
        assertThat(statusOf(response)).as("browse%s failed: %s", query, response.getBody()).isEqualTo(200);
        return jsonOf(response);
    }

    private List<String> idsIn(JSONObject page) {
        return idsIn(page.getJSONArray("items"));
    }

    private List<String> idsIn(JSONArray items) {
        List<String> ids = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            ids.add(items.getJSONObject(i).getString("id"));
        }
        return ids;
    }

    /** Publishes a free contribution, which skips review entirely. */
    private UUID publishFreeContribution(Actor contributor, String company, String teaser) {
        UUID id = createDraft(contributor,
                Payloads.freeContribution().put("company", company).put("teaser", teaser));
        addRound(contributor, id);
        ResponseEntity<String> submitted = post("/api/v1/experiences/" + id + "/submit", contributor, null);
        assertThat(statusOf(submitted)).isEqualTo(200);
        return id;
    }

    // --- Visibility --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-BROWSE-01: only published experiences appear")
    void onlyPublishedExperiencesAppear() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();

        UUID published = publishedExperience(contributor, admin,
                Payloads.experience("PublishedCo", "Engineer", "Visible teaser."));
        createDraft(contributor, Payloads.experience("DraftCo", "Engineer", "Draft teaser."));
        submittedExperience(contributor, Payloads.experience("PendingCo", "Engineer", "Pending teaser."));
        UUID rejected = submittedExperience(contributor, Payloads.experience("RejectedCo", "Engineer", "Rej."));
        post("/api/v1/admin/experiences/" + rejected + "/reject", admin, new JSONObject().put("reason", "no"));

        assertThat(idsIn(browse(""))).containsExactly(published.toString());
    }

    @Test
    @DisplayName("FT-BROWSE-02/17: a teaser exposes depth, never content")
    void teaserExposesDepthButNotContent() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();

        UUID experienceId = createDraft(contributor, Payloads.experience()
                .put("prepAdvice", "SECRET-PREP-ADVICE")
                .put("confidentialNote", "SECRET-CONFIDENTIAL-NOTE"));
        addRound(contributor, experienceId, Payloads.round("PHONE_SCREEN"));
        addRound(contributor, experienceId, Payloads.round("SYSTEM_DESIGN"));
        uploadProof(contributor, experienceId);
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);
        post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        ResponseEntity<String> response = getAnonymously("/api/v1/experiences");
        String rawBody = response.getBody();
        JSONObject item = jsonOf(response).getJSONArray("items").getJSONObject(0);

        // Round count signals how much content there is, which is a legitimate selling point.
        assertThat(item.getInt("roundCount")).isEqualTo(2);
        assertThat(item.getLong("pricePaise")).isEqualTo(DEFAULT_PRICE_PAISE);
        assertThat(item.getBoolean("unlocked")).isFalse();
        // Everything behind the paywall must be absent from the payload entirely — not merely
        // hidden by the UI. Checking the raw body too catches a field added to the DTO later.
        assertThat(item.has("rounds")).isFalse();
        assertThat(item.has("prepAdvice")).isFalse();
        assertThat(item.has("confidentialNote")).isFalse();
        assertThat(item.has("proofDocuments")).isFalse();
        assertThat(rawBody).doesNotContain("SECRET-PREP-ADVICE");
        assertThat(rawBody).doesNotContain("SECRET-CONFIDENTIAL-NOTE");
        assertThat(rawBody).doesNotContain("Design a rate limiter");
    }

    // --- Filters -----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-BROWSE-03: with no filters every published experience comes back")
    void noFiltersReturnsEverythingPublished() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin, Payloads.experience("Alpha", "Engineer", "One."));
        publishedExperience(contributor, admin, Payloads.experience("Beta", "Designer", "Two."));
        publishedExperience(contributor, admin, Payloads.experience("Gamma", "Engineer", "Three."));

        JSONObject page = browse("");

        // Every nullable filter takes its IS NULL branch here — the path most likely to blow up
        // on Postgres if a CAST is ever dropped from the query.
        assertThat(page.getLong("totalItems")).isEqualTo(3);
        assertThat(idsIn(page)).hasSize(3);
    }

    @Test
    @DisplayName("FT-BROWSE-04: the company filter is a case- and punctuation-insensitive contains")
    void companyFilterIsNormalizedContains() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID acme = publishedExperience(contributor, admin, Payloads.experience("Acme", "Engineer", "A."));
        publishedExperience(contributor, admin, Payloads.experience("Globex", "Engineer", "B."));

        assertThat(idsIn(browse("?company=acme"))).containsExactly(acme.toString());
        assertThat(idsIn(browse("?company=ACME"))).containsExactly(acme.toString());
        // Tier 1 (V15): filters are now a normalized "contains", not an exact match, so a
        // partial term matches — "Acm" finds "Acme". A term that isn't a substring of any
        // normalized company still returns nothing.
        assertThat(idsIn(browse("?company=Acm"))).containsExactly(acme.toString());
        assertThat(idsIn(browse("?company=Zzz"))).isEmpty();
    }

    @Test
    @DisplayName("FT-BROWSE-05/06: role, level and year filters work alone and combine with AND")
    void filtersWorkAloneAndCombine() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID target = publishedExperience(contributor, admin, Payloads.experience("Acme", "Backend", "T.")
                .put("level", "L5").put("interviewYear", 2025));
        publishedExperience(contributor, admin, Payloads.experience("Acme", "Frontend", "O.")
                .put("level", "L4").put("interviewYear", 2025));
        publishedExperience(contributor, admin, Payloads.experience("Globex", "Backend", "P.")
                .put("level", "L5").put("interviewYear", 2026));

        assertThat(idsIn(browse("?roleTitle=Backend"))).hasSize(2);
        assertThat(idsIn(browse("?level=L4"))).hasSize(1);
        assertThat(idsIn(browse("?year=2026"))).hasSize(1);
        // Combined: AND, not OR.
        assertThat(idsIn(browse("?company=Acme&level=L5&year=2025"))).containsExactly(target.toString());
        assertThat(idsIn(browse("?company=Acme&year=2026"))).isEmpty();
    }

    @Test
    @DisplayName("FT-BROWSE-07: the isFree filter partitions paid from free")
    void isFreeFilterPartitionsResults() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID paid = publishedExperience(contributor, admin, Payloads.experience("PaidCo", "Engineer", "Paid."));
        UUID free = publishFreeContribution(contributor, "FreeCo", "Free.");

        assertThat(idsIn(browse("?isFree=true"))).containsExactly(free.toString());
        assertThat(idsIn(browse("?isFree=false"))).containsExactly(paid.toString());
        assertThat(idsIn(browse(""))).hasSize(2);
    }

    @Test
    @DisplayName("FT-BROWSE-08/09: free-text search covers company, role and teaser")
    void searchCoversCompanyRoleAndTeaser() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID byCompany = publishedExperience(contributor, admin,
                Payloads.experience("Zephyrtech", "Engineer", "Nothing notable here."));
        UUID byRole = publishedExperience(contributor, admin,
                Payloads.experience("Acme", "Cartographer", "Nothing notable here."));
        UUID byTeaser = publishedExperience(contributor, admin,
                Payloads.experience("Globex", "Engineer", "Lots of pangolin questions."));

        assertThat(idsIn(browse("?search=zephyr"))).containsExactly(byCompany.toString());
        assertThat(idsIn(browse("?search=CARTOGRAPHER"))).containsExactly(byRole.toString());
        assertThat(idsIn(browse("?search=pangolin"))).containsExactly(byTeaser.toString());
        // FT-BROWSE-09: a miss is an empty page, not an error.
        JSONObject miss = browse("?search=zzzznothingmatchesthis");
        assertThat(miss.getJSONArray("items")).isEmpty();
        assertThat(miss.getLong("totalItems")).isZero();
    }

    // --- Paging and sorting ---------------------------------------------------------------------

    @Test
    @DisplayName("FT-BROWSE-10: paging covers every row exactly once")
    void pagingCoversEveryRowExactlyOnce() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        for (int i = 0; i < 5; i++) {
            publishedExperience(contributor, admin, Payloads.experience("Company" + i, "Engineer", "Teaser " + i));
        }

        JSONObject first = browse("?page=0&size=2");
        JSONObject second = browse("?page=1&size=2");
        JSONObject third = browse("?page=2&size=2");

        assertThat(first.getLong("totalItems")).isEqualTo(5);
        assertThat(first.getInt("totalPages")).isEqualTo(3);
        assertThat(first.getInt("page")).isZero();
        assertThat(first.getInt("pageSize")).isEqualTo(2);
        assertThat(idsIn(first)).hasSize(2);
        assertThat(idsIn(second)).hasSize(2);
        assertThat(idsIn(third)).hasSize(1);

        Set<String> seen = new HashSet<>();
        seen.addAll(idsIn(first));
        seen.addAll(idsIn(second));
        seen.addAll(idsIn(third));
        assertThat(seen).as("no row may be skipped or duplicated across pages").hasSize(5);
    }

    @Test
    @DisplayName("FT-BROWSE-11: negative paging parameters are clamped, not fatal (§1.7)")
    void negativePagingParametersAreClamped() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);

        // PageRequest.of throws IllegalArgumentException on a negative page or a size below 1,
        // which the catch-all handler would turn into a 500 for what is only a stale bookmark.
        JSONObject clamped = browse("?page=-1&size=0");

        assertThat(clamped.getInt("page")).isZero();
        assertThat(clamped.getInt("pageSize")).isGreaterThanOrEqualTo(1);
        assertThat(idsIn(clamped)).hasSize(1);
        assertThat(statusOf(getAnonymously("/api/v1/experiences?page=-99&size=-99"))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-BROWSE-12: an oversized page size is clamped to the configured maximum")
    void oversizedPageIsClamped() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin);

        JSONObject page = browse("?size=5000");

        // app.pagination.max-page-size — otherwise one request can ask the database for
        // everything at once.
        assertThat(page.getInt("pageSize")).isEqualTo(100);
    }

    @Test
    @DisplayName("FT-BROWSE-13/14: sort modes order correctly and an unknown mode falls back")
    void sortModesOrderCorrectly() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        publishedExperience(contributor, admin, Payloads.experience("PaidA", "Engineer", "A."));
        UUID paidTwo = publishedExperience(contributor, admin, Payloads.experience("PaidB", "Engineer", "B."));
        UUID free = publishFreeContribution(contributor, "FreeC", "C.");

        // priceLow puts the free (0 paise) one first; priceHigh puts it last.
        assertThat(idsIn(browse("?sort=priceLow")).get(0)).isEqualTo(free.toString());
        assertThat(idsIn(browse("?sort=priceHigh"))).endsWith(free.toString());

        // newest sorts by publishedAt descending, and the free one was published last.
        assertThat(idsIn(browse("?sort=newest")).get(0)).isEqualTo(free.toString());
        // FT-BROWSE-14: an unrecognised value falls back rather than breaking the page.
        assertThat(idsIn(browse("?sort=totally-made-up"))).isEqualTo(idsIn(browse("?sort=newest")));

        // mostViewed: one view is enough to climb above the untouched zeroes.
        get("/api/v1/experiences/" + paidTwo, registerUser());
        assertThat(idsIn(browse("?sort=mostViewed")).get(0)).isEqualTo(paidTwo.toString());
        assertThat(idsIn(browse("?sort=mostViewed"))).hasSize(3);
    }

    // --- Per-caller state -----------------------------------------------------------------------

    @Test
    @DisplayName("FT-BROWSE-15: 'unlocked' reflects the calling viewer, not the experience")
    void unlockedReflectsTheCallingViewer() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        Actor otherViewer = registerUser();
        UUID bought = publishedExperience(contributor, admin, Payloads.experience("BoughtCo", "Engineer", "B."));
        UUID notBought = publishedExperience(contributor, admin, Payloads.experience("OtherCo", "Engineer", "N."));
        purchase(buyer, bought);

        JSONArray asBuyer = jsonOf(get("/api/v1/experiences", buyer)).getJSONArray("items");
        assertThat(idsIn(asBuyer)).containsExactlyInAnyOrder(bought.toString(), notBought.toString());
        for (int i = 0; i < asBuyer.length(); i++) {
            JSONObject item = asBuyer.getJSONObject(i);
            assertThat(item.getBoolean("unlocked"))
                    .as("unlocked flag for %s", item.getString("company"))
                    .isEqualTo(item.getString("id").equals(bought.toString()));
        }

        // Another signed-in viewer sees nothing unlocked...
        JSONArray asOther = jsonOf(get("/api/v1/experiences", otherViewer)).getJSONArray("items");
        for (int i = 0; i < asOther.length(); i++) {
            assertThat(asOther.getJSONObject(i).getBoolean("unlocked")).isFalse();
        }
        // ...and neither does a guest, for whom the entitlement lookup is skipped entirely.
        JSONArray asGuest = jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items");
        for (int i = 0; i < asGuest.length(); i++) {
            assertThat(asGuest.getJSONObject(i).getBoolean("unlocked")).isFalse();
        }
    }

    @Test
    @DisplayName("FT-BROWSE-16: a free experience reads as unlocked even to a guest")
    void freeExperienceReadsAsUnlockedForEveryone() {
        Actor contributor = registerUser();
        publishFreeContribution(contributor, "FreeCo", "Free for everyone.");

        JSONObject item = jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items").getJSONObject(0);

        assertThat(item.getBoolean("isFree")).isTrue();
        assertThat(item.getBoolean("unlocked")).isTrue();
        assertThat(item.getLong("pricePaise")).isZero();
    }
}
