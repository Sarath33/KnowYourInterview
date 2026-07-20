package com.knowyourinterview.api.security;

import java.util.UUID;

/** Principal attached to the SecurityContext for a request authenticated via JWT. */
public record AuthenticatedUser(UUID id, String email, boolean admin) {
}
