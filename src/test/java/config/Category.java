package config;

import com.electronwill.nightconfig.core.serde.annotations.SerdeKey;
import com.electronwill.nightconfig.core.serde.annotations.SerdeSkipDeserializingIf;
import com.electronwill.nightconfig.core.serde.annotations.SerdeSkipDeserializingIf.SkipDeIf;

public class Category {
    @SerdeKey("field_in_category")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    @SuppressWarnings("unused")
    public String field_in_category = "";
}
