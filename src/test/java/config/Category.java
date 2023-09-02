package config;

import com.electronwill.nightconfig.core.conversion.Path;
import com.electronwill.nightconfig.core.conversion.PreserveNotNull;

public class Category {
    @Path("field_in_category")
    @PreserveNotNull
    @SuppressWarnings("unused")
    public String field_in_category = "";
}
