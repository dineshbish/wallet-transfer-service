package com.dinesh.wallet.wallet;

import com.dinesh.wallet.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * Get-or-create the wallet for the authenticated caller. The bearer token
     * identifies the user, so there is no user id in the body.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreate() {
        Wallet wallet = walletService.getOrCreate(AuthContext.requireUserId());
        return ResponseEntity.status(HttpStatus.OK).body(WalletResponse.from(wallet));
    }

    /** Returns the balance only if the authenticated caller owns the wallet (else 403). */
    @GetMapping("/{id}")
    public WalletResponse getById(@PathVariable UUID id) {
        String caller = AuthContext.requireUserId();
        return WalletResponse.from(walletService.getOwnedById(caller, id));
    }

    /**
     * Adds outside funds to a wallet the caller owns (the funding boundary used to
     * seed balances). Idempotent on {@code idempotencyKey}.
     */
    @PostMapping("/{id}/deposit")
    public WalletResponse deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
        String caller = AuthContext.requireUserId();
        Wallet wallet = walletService.deposit(caller, id, request.amountPaise(), request.idempotencyKey());
        return WalletResponse.from(wallet);
    }
}
