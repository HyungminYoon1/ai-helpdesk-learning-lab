package lab.helpdesk.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketMessageBodyLengthTest {

    @Test
    void accepts_2000_code_points_and_preserves_original_whitespace() {
        String original = "\u2003\n" + "😀".repeat(2000) + "\t\u2003";
        TicketMessage message = new TicketMessage(original, "synthetic-author");
        assertThat(TicketMessage.bodyCodePointCount(original)).isEqualTo(2000);
        assertThat(message.body()).isEqualTo(original);
    }

    @Test
    void rejects_2001_code_points_without_truncation() {
        assertThatThrownBy(() -> new TicketMessage("😀".repeat(2001), "synthetic-author"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message body exceeds allowed code point count");
    }

    @Test
    void counts_internal_whitespace_as_part_of_body() {
        assertThatThrownBy(() -> new TicketMessage("a" + " ".repeat(1999) + "b", "author"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unicode_whitespace_only_body() {
        assertThatThrownBy(() -> new TicketMessage("\u2003\t\n", "author"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message body must not be blank");
    }
}
