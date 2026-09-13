package com.example.wallet.controller;

import com.example.wallet.model.TransferResponse;
import com.example.wallet.observability.TransferTelemetry;
import com.example.wallet.service.TransferService;
import com.example.wallet.validation.Input;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/transfers")
public class TransferController {
    private final TransferService service;
    private final TransferTelemetry telemetry;
    public TransferController(TransferService service, TransferTelemetry telemetry) {
        this.service = service;
        this.telemetry = telemetry;
    }

    @PostMapping(consumes = "application/json")
    public TransferResponse create(Principal user, @RequestBody JsonNode body) {
        var result = service.transfer(user.getName(), Input.transfer(body));
        telemetry.committed(result);
        return result.transfer();
    }

    @PostMapping(value = "/{id}/reverse", consumes = "application/json")
    public TransferResponse reverse(Principal user, @PathVariable String id, @RequestBody JsonNode body) {
        var result = service.reverse(user.getName(), Input.id(id), Input.reversal(body));
        telemetry.committed(result);
        return result.transfer();
    }

    @GetMapping("/{id}")
    public TransferResponse get(Principal user, @PathVariable String id) {
        return service.getTransfer(user.getName(), Input.id(id));
    }
}
