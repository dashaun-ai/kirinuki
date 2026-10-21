package ai.dashaun.kirinuki.review;

public enum ContentField {
    SUMMARY(false),
    KEYWORDS(false),
    TAGS(false),
    HASHTAGS(true),
    TWEET(true),
    THREAD_SUGGESTION(true),
    TITLE(true),
    DESCRIPTION(true),
    SEO_TAGS(true),
    POST(true),
    CAROUSEL_HOOK(true),
    CALL_TO_ACTION(true),
    CAPTION(true),
    HOOK_TEXT(true);

    private final boolean platformScoped;

    ContentField(boolean platformScoped) {
        this.platformScoped = platformScoped;
    }

    public boolean isPlatformScoped() {
        return platformScoped;
    }
}
