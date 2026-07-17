package com.reiter.autostack.core.engine;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VendorExtractionRegistry {

    private final Map<String, DeterministicExtractor> registry;

    public VendorExtractionRegistry(List<DeterministicExtractor> extractors) {
        // Mapeo automático polimórfico usando el código del proveedor como clave
        this.registry = extractors.stream()
                .collect(Collectors.toMap(DeterministicExtractor::getVendorCode, Function.identity()));
    }

    public Optional<DeterministicExtractor> getExtractor(String vendorCode) {
        return Optional.ofNullable(registry.get(vendorCode));
    }
}