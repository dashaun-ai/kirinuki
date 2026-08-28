package ai.dashaun.kirinuki.dashboard;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ClipEditForm {

    private Long version;
    private String summary;
    private String keywords;
    private String tags;
    private List<PlatformEditForm> platforms = new ArrayList<>();
}
