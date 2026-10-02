package com.orbit.assistant;

import java.util.List;

/** Presentation-only de-duplication for source controls; stored citations remain untouched. */
final class RichAnswerSourcePresentation {
    private RichAnswerSourcePresentation() {}

    static boolean isAlreadyAttributed(String sourceUrl, List<RichAnswerImage> images) {
        String wanted = RichAnswerUrlPolicy.normalizedForRequest(sourceUrl);
        if (wanted.isEmpty() || images == null) return false;
        for (RichAnswerImage image : images) {
            if (image == null || !image.isUsable()) continue;
            if (wanted.equals(RichAnswerUrlPolicy.normalizedForRequest(image.sourceUrl))) return true;
        }
        return false;
    }
}
