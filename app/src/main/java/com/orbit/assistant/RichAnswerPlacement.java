package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Where inside an answer a picture is drawn.
 *
 * <p>The obvious implementation is to put every image at the bottom, and it is the wrong one: an
 * answer with its picture appended underneath reads as a message with an attachment on it rather
 * than as an answer that happens to contain a photograph. The picture belongs where somebody would
 * have put it if they had written the answer by hand, which is after the sentence that first tells
 * you what you are looking at.
 *
 * <p>The other obvious implementation is semantic: work out which paragraph the image illustrates.
 * That needs an inference Orbit cannot make cheaply or reliably, and getting it wrong puts a
 * photograph of a bird under a paragraph about migration seasons. So this is deterministic instead:
 * after the first block that is genuinely prose, and never in the middle of a construct that would
 * be broken by having something inserted into it.
 *
 * <p>Pure index arithmetic over {@link ResponseBlocks.Block}s, which is what makes it testable and
 * what keeps it identical in the streaming path and the completed one. A stored index that no
 * longer fits - the answer was re-clipped, edited, or restored from a backup - is clamped rather
 * than dropped, so a picture is never lost because its anchor moved.
 */
public final class RichAnswerPlacement {

    private RichAnswerPlacement() {}

    /**
     * The block index a newly discovered picture should be drawn after.
     *
     * <p>The first paragraph is the one that says what the thing is. A heading is not: an answer
     * beginning "## European robin" would put the picture between the title and its first line,
     * which reads as a banner rather than as part of the answer. A one-block answer places after
     * that block, which is the only place there is.
     */
    public static int placementFor(List<ResponseBlocks.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return 0;
        for (int i = 0; i < blocks.size(); i++) {
            ResponseBlocks.Block block = blocks.get(i);
            if (block == null) continue;
            if (block.kind == ResponseBlocks.Kind.PARAGRAPH || block.kind == ResponseBlocks.Kind.LIST) {
                return i;
            }
        }
        return blocks.size() - 1;
    }

    /** The same question asked of the raw answer text. */
    public static int placementFor(String answerText) {
        return placementFor(ResponseBlocks.parse(answerText == null ? "" : answerText));
    }

    /**
     * The pictures to draw after one block, in stored order.
     *
     * <p>Every image is placed somewhere: an anchor past the end of a shorter answer settles on the
     * last block rather than being silently discarded, because the user saved, tapped and looked at
     * that picture and it is still part of what Orbit said.
     */
    public static List<RichAnswerImage> imagesAfter(List<RichAnswerImage> images, int blockIndex,
                                                    int blockCount) {
        List<RichAnswerImage> out = new ArrayList<>();
        if (images == null || images.isEmpty() || blockCount <= 0) return out;
        int last = blockCount - 1;
        for (RichAnswerImage image : images) {
            if (image == null) continue;
            int anchor = Math.min(Math.max(0, image.blockIndex), last);
            if (anchor == blockIndex) out.add(image);
        }
        return out;
    }

    /**
     * Whether an answer of this many blocks has any picture at all to draw.
     *
     * <p>Cheap enough to ask before building a view tree, so an ordinary answer - which is nearly
     * all of them - pays nothing for a feature it is not using.
     */
    public static boolean hasAnyImage(List<RichAnswerImage> images) {
        if (images == null) return false;
        for (RichAnswerImage image : images) if (image != null && image.isUsable()) return true;
        return false;
    }
}
