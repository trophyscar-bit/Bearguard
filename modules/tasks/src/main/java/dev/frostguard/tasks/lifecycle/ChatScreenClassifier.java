package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

import java.util.Optional;

/** Recognises the Chat screen from its fixed header and finds the screen's own close control. */
final class ChatScreenClassifier {

    private static final AreaData TITLE_AREA = AreaData.of(70, 8, 200, 72);
    private static final AreaData CLOSE_AREA = AreaData.of(640, 8, 719, 72);
    private static final int THRESHOLD = 90;

    private ChatScreenClassifier() {
    }

    /** The close control, present only when the Chat title and the control share the frame. */
    static Optional<ImageSearchResultData> locateCloseControl(RawImageData frame) {
        if (!locate(frame, TemplatesEnum.GAME_CHAT_SCREEN_TITLE, TITLE_AREA).isFound()) {
            return Optional.empty();
        }
        ImageSearchResultData close = locate(frame, TemplatesEnum.GAME_CHAT_SCREEN_CLOSE, CLOSE_AREA);
        return close.isFound() ? Optional.of(close) : Optional.empty();
    }

    private static ImageSearchResultData locate(RawImageData frame, TemplatesEnum template, AreaData area) {
        return OpenCvPatternLocator.locatePattern(frame, template.getTemplate(),
                area.topLeft(), area.bottomRight(), THRESHOLD);
    }
}
