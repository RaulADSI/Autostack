package com.reiter.autostack.core.engine;

import com.reiter.autostack.intelligence.config.VendorProperties;

import com.reiter.autostack.core.engine.DeterministicVendorDetector.VendorFingerprint;
import com.reiter.autostack.core.engine.DeterministicVendorDetector.VendorRule;
import com.reiter.autostack.core.engine.DeterministicVendorDetector.RegexRule;
import com.reiter.autostack.core.engine.DeterministicVendorDetector.FuzzyRule;
import com.reiter.autostack.core.engine.DeterministicVendorDetector.AnchorRule;
import com.reiter.autostack.core.engine.DeterministicVendorDetector.NegativeRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class VendorFingerprintCatalog {

    private final List<VendorFingerprint> fingerprints;

    public VendorFingerprintCatalog(VendorProperties properties) {
        List<VendorFingerprint> technicalCatalog = new ArrayList<>();

        for (VendorProperties.VendorConfig config : properties.getVendors()) {
            List<VendorRule> rules = new ArrayList<>();

            for (VendorProperties.RuleConfig ruleConfig : config.getRules()) {
                String type = ruleConfig.getType().toUpperCase();
                String arg = ruleConfig.getArgument();
                int score = ruleConfig.getScore();
                int param = ruleConfig.getParam();

                if ("REGEX".equals(type) || "NEGATIVE".equals(type)) {
                    assertSafeRegexPattern(arg);
                }

                switch (type) {
                    case "REGEX" -> rules.add(new RegexRule(Pattern.compile(arg, Pattern.CASE_INSENSITIVE), score));
                    case "FUZZY" -> rules.add(new FuzzyRule(arg, param, score));
                    case "ANCHOR" -> rules.add(new AnchorRule(Arrays.asList(arg.split(",")), param, score));
                    case "NEGATIVE" -> rules.add(new NegativeRule(Pattern.compile(arg, Pattern.CASE_INSENSITIVE), score));
                }
            }

            technicalCatalog.add(new VendorFingerprint(
                    config.getVendorCode(),
                    config.getVersion(),
                    config.getMinimumConfidence(),
                    rules
            ));
        }

        this.fingerprints = List.copyOf(technicalCatalog);
    }

    public List<VendorFingerprint> getFingerprints() {
        return this.fingerprints;
    }

    private void assertSafeRegexPattern(String expression) {
        try {
            Pattern.compile(expression);

            String clean = expression.replaceAll("\\s+", "");
            if (clean.contains(")+") || clean.contains(")*") || clean.contains(".*+") || clean.contains(".++")) {
                throw new IllegalArgumentException("Nested quantifier hazard. High risk of thread starvation via Catastrophic Backtracking.");
            }
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("Bootstrap blocked: Broken regex syntax detected -> " + expression, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Bootstrap blocked: Security audit rejected regex structure -> " + expression, e);
        }
    }
}