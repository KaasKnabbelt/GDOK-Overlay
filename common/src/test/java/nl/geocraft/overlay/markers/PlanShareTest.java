package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Deel" in the G menu (BOMEN.md §9.9): what may end up in the chat box. */
class PlanShareTest {

    private final List<String> messages = new ArrayList<>();
    private final List<String> chat = new ArrayList<>();

    @BeforeEach
    void ui() {
        PlanShare.getInstance().reset();
        PlanShare.getInstance().setUi(new PlanShare.Ui() {
            @Override
            public void message(String text) {
                messages.add(text);
            }

            @Override
            public void chat(String text) {
                chat.add(text);
            }
        });
    }

    @Test
    void onlyAGdokLinkToThePasteCommandsIsAccepted() {
        assertTrue(PlanShare.isShareUrl("https://gdok.nl/bomen/k3F9x2ab#paste"));
        assertTrue(PlanShare.isShareUrl("https://www.gdok.nl/bomen/k3F9x2ab#paste"));
        assertTrue(PlanShare.isShareUrl("http://localhost:8000/bomen/k3F9x2ab#paste"));
        assertTrue(PlanShare.isShareUrl("http://127.0.0.1/bomen/abcd#paste"));

        assertFalse(PlanShare.isShareUrl(null));
        assertFalse(PlanShare.isShareUrl("https://gdok.nl/bomen/k3F9x2ab"), "zonder #paste");
        assertFalse(PlanShare.isShareUrl("http://gdok.nl/bomen/k3F9x2ab#paste"), "alleen https");
        assertFalse(PlanShare.isShareUrl("https://gdok.nl.evil.example/bomen/k3F9x2ab#paste"));
        assertFalse(PlanShare.isShareUrl("https://evil.example/bomen/k3F9x2ab#paste"));
        assertFalse(PlanShare.isShareUrl("https://gdok.nl/bomen/k3F9x2ab#paste /op Someone"));
        assertFalse(PlanShare.isShareUrl("https://gdok.nl/bomen/../admin#paste"));
        assertFalse(PlanShare.isShareUrl("/geotools paste red_wool -a -r -s gdok"));
        assertFalse(PlanShare.isShareUrl("https://gdok.nl/bomen/k3F9x2ab#paste\n/op Someone"));
    }

    @Test
    void thePlanNameLosesEverythingThatDoesNotBelongInChat() {
        assertEquals("St. Anna (noord)", PlanShare.plainLabel("St. Anna (noord)"));
        assertEquals("Caf\u00e9 't Hoekje", PlanShare.plainLabel("Caf\u00e9 't Hoekje"));
        assertEquals("Park cRood", PlanShare.plainLabel("Park \u00a7cRood"), "geen sectieteken");
        assertEquals("op me", PlanShare.plainLabel("/op me"), "geen schuine streep vooraan");
        assertEquals("a b", PlanShare.plainLabel("a\n\tb"));
        assertEquals("bomenplan", PlanShare.plainLabel("\u00a7\u00a7"));
        assertEquals(40, PlanShare.plainLabel("x".repeat(100)).length());
        String text = Messages.shareChatText(PlanShare.plainLabel("x".repeat(100)), "https://gdok.nl/bomen/" + "a".repeat(32) + "#paste");
        assertTrue(text.length() <= 256, "past in een chatbericht: " + text.length());
        assertFalse(text.startsWith("/"), "een bericht, geen commando");
    }

    @Test
    void aPlanThatIsGoneGivesAMessageAndNoChat() {
        PlanShare.getInstance().request("bestaat-niet");
        assertEquals(List.of(Messages.pastePlanGone()), messages);
        assertFalse(PlanShare.getInstance().isPending());
        assertTrue(chat.isEmpty());
    }

    @Test
    void anAnswerNobodyAskedForNeverReachesTheChatBox() {
        PlanShare.getInstance().submitAnswer("plan-1", "https://gdok.nl/bomen/k3F9x2ab#paste", null);
        PlanShare.getInstance().tick();
        PlanShare.getInstance().tick();
        assertTrue(chat.isEmpty());
        assertTrue(messages.isEmpty());
    }
}
