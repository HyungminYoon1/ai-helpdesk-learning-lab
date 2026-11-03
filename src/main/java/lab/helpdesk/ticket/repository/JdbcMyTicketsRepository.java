package lab.helpdesk.ticket.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lab.helpdesk.ticket.TicketStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcMyTicketsRepository implements MyTicketsRepository {
    private final JdbcTemplate jdbc;
    public JdbcMyTicketsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Item> find(long requesterId, Cursor cursor, int count) {
        var args = new ArrayList<Object>();
        args.add(requesterId);
        String boundary = "";
        if (cursor != null && cursor.lastActivityAt() == null) {
            boundary = " AND activity.created_at IS NULL AND t.id < ?";
            args.add(cursor.ticketId());
        } else if (cursor != null) {
            boundary = " AND (activity.created_at < ? OR (activity.created_at = ? AND t.id < ?) OR activity.created_at IS NULL)";
            var time = OffsetDateTime.ofInstant(cursor.lastActivityAt(), ZoneOffset.UTC);
            args.add(time); args.add(time); args.add(cursor.ticketId());
        }
        args.add(count);
        return jdbc.query("""
                SELECT t.id,t.title,t.status,activity.created_at AS last_activity_at
                FROM tickets t
                LEFT JOIN LATERAL (
                    SELECT m.created_at FROM ticket_messages m
                    WHERE m.ticket_id=t.id AND m.message_type IN ('INQUIRY','CUSTOMER_FOLLOWUP','OFFICIAL_REPLY')
                    ORDER BY m.id DESC LIMIT 1
                ) activity ON TRUE
                WHERE t.requester_user_id=?
                """ + boundary + " ORDER BY activity.created_at DESC NULLS LAST,t.id DESC LIMIT ?",
                (row, index) -> {
                    var time = row.getObject("last_activity_at", OffsetDateTime.class);
                    return new Item(row.getLong("id"), row.getString("title"), TicketStatus.valueOf(row.getString("status")),
                            time == null ? null : time.toInstant());
                }, args.toArray());
    }
}
