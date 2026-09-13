package com.example.wallet.controller;

import com.example.wallet.model.WalletResponse;
import com.example.wallet.service.WalletService;
import com.example.wallet.validation.Input;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final WalletService service;
    public WalletController(WalletService service) { this.service = service; }

    @PostMapping(consumes = "application/json")
    public WalletResponse create(Principal user, @RequestBody JsonNode body) {
        Input.wallet(body, user.getName());
        return service.getOrCreate(user.getName());
    }

    @GetMapping("/{id}")
    public WalletResponse get(Principal user, @PathVariable String id) {
        return service.getWallet(user.getName(), Input.id(id));
    }
}
