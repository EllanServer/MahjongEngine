package top.ellan.mahjong.presentation.label;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionLabelPolicyTest {
    @Test
    void preservesV15VisualBudgetAndWidthBuckets() {
        assertEquals("Declare missing sui…", ActionLabelPolicy.compactActionLabel(
                "Declare missing suit Characters"));
        assertEquals(0.7D, ActionLabelPolicy.buttonWidth("吃"));
        assertEquals(2.2D, ActionLabelPolicy.buttonWidth("Declare missing suit Characters"));
        assertEquals("070", ActionLabelPolicy.variantSuffix(0));
        assertEquals("220", ActionLabelPolicy.variantSuffix(15));
    }

    @Test
    void cjkCharactersConsumeTwoVisualUnits() {
        assertEquals(4, ActionLabelPolicy.visualUnits("吃碰"));
        assertTrue(ActionLabelPolicy.visualUnits("一萬") > ActionLabelPolicy.visualUnits("1m"));
    }
}
