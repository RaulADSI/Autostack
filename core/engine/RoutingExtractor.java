package com.reiter.autostack.core.engine;

import java.util.Optional;

/**
 * Contrato unificado y minimalista para el Conmutador de Enrutamiento del MVP.
 */
public interface RoutingExtractor {

    /**
     * Evalúa si las firmas fuertes del proveedor coexisten dentro del texto extraído.
     */
    boolean matches(String text);

    /**
     * Código único del proveedor (ej. "WM", "FPL", "COMCAST").
     */
    String vendorCode();

    /**
     * Extrae y normaliza la llave relacional estable (cuenta, dirección, medidor) para cruzar con el CSV.
     */
    Optional<String> extractRoutingKey(String text);
}