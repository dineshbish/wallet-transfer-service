package com.dinesh.wallet.transfer;

import com.dinesh.wallet.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Always responds 200 OK with the transfer resource (status COMPLETED or
     * DECLINED). A retry with the same idempotency key returns the identical body,
     * so a storm of K concurrent retries yields K identical responses.
     */
    @PostMapping
    public TransferResponse create(@Valid @RequestBody TransferRequest request) {
        String caller = AuthContext.requireUserId(); // 401 if unauthenticated
        // The caller (from the token) must own the `from` wallet; the service
        // rejects a transfer out of a wallet the caller does not own (403).
        Transfer transfer = transferService.transfer(
                caller, request.from(), request.to(), request.amountPaise(), request.idempotencyKey());
        return TransferResponse.from(transfer);
    }

    /** Returns the transfer only if the caller is a party to it (sender or recipient), else 403. */
    @GetMapping("/{id}")
    public TransferResponse getById(@PathVariable UUID id) {
        String caller = AuthContext.requireUserId();
        return TransferResponse.from(transferService.getVisibleById(caller, id));
    }
}
