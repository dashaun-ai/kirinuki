package ai.dashaun.kirinuki.edit;

import java.util.UUID;

import ai.dashaun.kirinuki.common.KirinukiException;

public class DesignedEditNotFoundException extends KirinukiException {

    public DesignedEditNotFoundException(UUID videoId, int clipIndex) {
        super("Designed edit not found for video " + videoId + " clip " + clipIndex);
    }
}
