package ai.dashaun.kirinuki.dashboard;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PlatformEditForm {

    private String platform;
    private String title;
    private String caption;
    private String hashtags;
    private String callToAction;
}
