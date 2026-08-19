package lab.helpdesk.ticket;

public final class Ticket {

    private final String title;
    private TicketStatus status = TicketStatus.OPEN;

    public String title() {
        return title;
    }

    public Ticket(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "title must not be blank");
        }

        this.title = title;
    }

    // 티켓 상태를 '처리중'으로 변경
    public void startProgress() {
        if (status != TicketStatus.OPEN) {
            throw new IllegalStateException(
                    "only OPEN ticket can start progress");
        }

        status = TicketStatus.IN_PROGRESS;
    }

    // 티켓 상태를 '해결'로 변경
    public void resolve() {
        if (status != TicketStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "only IN_PROGRESS ticket can be resolved");
        }

        status = TicketStatus.RESOLVED;
    }

    // 현재 티켓 상태 호출
    public TicketStatus status() {
        return status;
    }
}
