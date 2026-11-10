package amazon.api.commerce;

/** Only an allowlisted reason code; do not collect personal details. */
public record ReturnRequest(String reasonCode) {}
