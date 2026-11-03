package lab.helpdesk.ticket.web;

import java.util.List;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.ticket.application.MyTicketsQueryService;
import lab.helpdesk.ticket.repository.MyTicketsRepository.Item;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
public class MyTicketsController {
    public record Page(String nextCursor, boolean hasMore) { }
    public record Result(List<Item> items, Page page) { }
    private final MyTicketsQueryService service;
    public MyTicketsController(MyTicketsQueryService service) { this.service = service; }

    @GetMapping("/api/my/tickets")
    public ResponseEntity<Result> list(@RequestParam MultiValueMap<String,String> params, Authentication authentication) {
        var query = MyTicketsQuery.parse(params);
        var result = service.read(query.limit(), query.cursor(), AuthenticatedActorFactory.from(authentication));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Result(result.items(),
                new Page(MyTicketsQuery.encode(result.nextCursor()),result.hasMore())));
    }
}
