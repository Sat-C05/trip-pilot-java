package com.trippilot.provider;

import static com.trippilot.provider.ProviderContracts.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class ProviderController {
    private final ProviderStore store;
    public ProviderController(ProviderStore store){this.store=store;}
    @PostMapping("/inventory") public Inventory inventory(@RequestBody Scope scope){return store.inventory(scope);}
    @PostMapping("/quotes") public Quote quote(@RequestBody QuoteRequest request){return store.quote(request);}
    @GetMapping("/quotes/{id}") public Quote quote(@PathVariable String id){return store.readQuote(id);}
    @PostMapping("/holds") public Hold hold(@RequestBody HoldRequest request){return store.hold(request);}
    @PostMapping("/bookings") public Status submit(@RequestBody Submit request){return store.submit(request);}
    @GetMapping("/bookings/{key}") public Status status(@PathVariable String key){return store.status(key);}
}
