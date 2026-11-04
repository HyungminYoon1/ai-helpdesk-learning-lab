package lab.helpdesk.ticket.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lab.helpdesk.ticket.TicketStatusAction;
import lab.helpdesk.ticket.application.TicketStatusException;
import lab.helpdesk.ticket.application.TicketStatusService.Command;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static lab.helpdesk.ticket.application.TicketStatusException.Code.*;

public final class CreateTicketStatusChangeRequest {
    private static final int MAX_BYTES=32*1024;
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private CreateTicketStatusChangeRequest() { }
    static Command readStaff(InputStream input) {return read(input,true);}
    static Command readRequester(InputStream input) {return read(input,false);}
    private static Command read(InputStream input,boolean staff) {
        try {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw new TicketStatusException(REQUEST_TOO_LARGE);
            var json=JSON.readTree(bytes);
            if(json==null || !json.isObject())throw invalid();
            var action=staff?TicketStatusAction.valueOf(text(json,"action")):TicketStatusAction.REOPEN;
            var fields=new HashSet<>(Set.of("clientRequestId","expectedStatusVersion","reason"));
            if(staff)fields.addAll(Set.of("action","expectedAssignmentVersion"));
            if(action==TicketStatusAction.RESOLVE)fields.addAll(Set.of("relatedReplyMessageId","throughCustomerMessageId"));
            if(json.size()!=fields.size() || !fields.containsAll(json.propertyNames()))throw invalid();
            String raw=text(json,"clientRequestId");var key=UUID.fromString(raw);
            if(!key.toString().equalsIgnoreCase(raw))throw invalid();
            long state=number(json,"expectedStatusVersion",0);
            Long assignment=staff?number(json,"expectedAssignmentVersion",1):null;
            return new Command(key,action,state,assignment,text(json,"reason"),
                    action==TicketStatusAction.RESOLVE?number(json,"relatedReplyMessageId",1):null,
                    action==TicketStatusAction.RESOLVE?number(json,"throughCustomerMessageId",1):null);
        } catch(IOException | JacksonException | IllegalArgumentException malformed) {throw invalid();}
    }
    private static String text(JsonNode json,String field) {var n=json.path(field);if(!n.isString())throw invalid();return n.asString();}
    private static long number(JsonNode json,String field,long minimum) {
        var n=json.path(field);
        if(!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<minimum
                || field.startsWith("expected") && n.asLong()==Long.MAX_VALUE)throw invalid();
        return n.asLong();
    }
    private static TicketStatusException invalid() {return new TicketStatusException(INVALID_STATUS_REQUEST);}
}
