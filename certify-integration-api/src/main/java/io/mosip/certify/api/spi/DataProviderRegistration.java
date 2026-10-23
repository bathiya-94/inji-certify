package io.mosip.certify.api.spi;
import java.util.Objects;

/** A delegate and validator form one registration; the router is never a delegate. */
public record DataProviderRegistration(String id, DataProviderPlugin plugin, LookupValidator validator,
                                       CredentialTemplateCustomizer templateCustomizer) {
    public DataProviderRegistration {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Provider ID is required");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(templateCustomizer, "templateCustomizer");
        if (plugin instanceof LookupDataProviderPlugin) throw new IllegalArgumentException("Router cannot be a delegate");
    }
    public DataProviderRegistration(String id, DataProviderPlugin plugin, LookupValidator validator) {
        this(id, plugin, validator, parameters -> { });
    }
}
