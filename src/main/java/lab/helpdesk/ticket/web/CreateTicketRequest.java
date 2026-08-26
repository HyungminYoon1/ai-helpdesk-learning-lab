package lab.helpdesk.ticket.web;

// Client가 보낸 다음 JSON을 Java 객체로 받기 위한 데이터 그릇
/*
   {
    "title": "로그인 오류"
    }
 */
public record CreateTicketRequest(
        String title) {
}
