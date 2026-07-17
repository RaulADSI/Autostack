package com.reiter.autostack.intelligence.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "autostack")
@Validated
public class VendorProperties {

    @NotEmpty(message = "El catálogo de proveedores no puede estar vacío.")
    private List<@Valid VendorConfig> vendors;

    public List<VendorConfig> getVendors() { return vendors; }
    public void setVendors(List<VendorConfig> vendors) { this.vendors = vendors; }

    public static class VendorConfig {
        @NotBlank(message = "El código de proveedor es mandatorio.")
        private String vendorCode;

        @Min(value = 1, message = "La versión del layout debe ser mayor a 0.")
        private int version;

        @DecimalMin(value = "0.0", message = "La confianza mínima no puede ser inferior a 0.0")
        @DecimalMax(value = "1.0", message = "La confianza mínima no puede exceder el 1.0")
        private double minimumConfidence;

        @NotEmpty(message = "Un proveedor debe contener al menos una regla.")
        private List<@Valid RuleConfig> rules;

        public String getVendorCode() { return vendorCode; }
        public void setVendorCode(String vendorCode) { this.vendorCode = vendorCode; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public double getMinimumConfidence() { return minimumConfidence; }
        public void setMinimumConfidence(double minimumConfidence) { this.minimumConfidence = minimumConfidence; }
        public List<RuleConfig> getRules() { return rules; }
        public void setRules(List<RuleConfig> rules) { this.rules = rules; }
    }

    public static class RuleConfig {
        @NotBlank(message = "El tipo de regla es obligatorio.")
        private String type;

        @NotBlank(message = "El argumento de búsqueda no puede estar en blanco.")
        private String argument;

        private int param;

        @Min(value = -500, message = "La penalización máxima permitida es -500.")
        @Max(value = 500, message = "El puntaje positivo máximo es 500.")
        private int score;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getArgument() { return argument; }
        public void setArgument(String argument) { this.argument = argument; }
        public int getParam() { return param; }
        public void setParam(int param) { this.param = param; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
    }
}