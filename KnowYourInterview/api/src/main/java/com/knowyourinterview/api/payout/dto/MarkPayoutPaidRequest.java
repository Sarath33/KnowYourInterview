package com.knowyourinterview.api.payout.dto;

import jakarta.validation.constraints.Size;

/** Reference is optional free text — a UPI transaction ID or bank reference number the
 * admin used to actually send the money, kept here purely for their own reconciliation. */
public record MarkPayoutPaidRequest(@Size(max = 255) String reference) {
}
