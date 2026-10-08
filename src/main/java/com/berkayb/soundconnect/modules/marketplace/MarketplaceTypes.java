package com.berkayb.soundconnect.modules.marketplace;

public final class MarketplaceTypes {
    private MarketplaceTypes() {}
    public enum Status { DRAFT, PUBLISHED, SOLD, WITHDRAWN, MODERATED }
    public enum Condition { NEW, USED }
    public enum Delivery { PICKUP, SHIPPING, BOTH }
    public enum SortOrder { NEWEST, PRICE_ASC, PRICE_DESC }
    public enum ReportReason { SCAM, MISLEADING, PROHIBITED, SPAM, OTHER }
    public enum ReportStatus { OPEN, DISMISSED, ACTIONED }
    public enum ReportDecision { DISMISS, REMOVE_LISTING }
}
