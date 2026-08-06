package ai.dashaun.kirinuki.review;

public enum ContentField {
    SUMMARY(false),
    KEYWORDS(false),
    TAGS(false),
    TITLE(true),
    CAPTION(true),
    HASHTAGS(true),
    CALL_TO_ACTION(true);

    private final boolean platformScoped;

    ContentField(boolean platformScoped) {
        this.platformScoped = platformScoped;
    }

    public boolean isPlatformScoped() {
        return platformScoped;
    }
}
