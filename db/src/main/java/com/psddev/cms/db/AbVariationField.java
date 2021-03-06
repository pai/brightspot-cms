package com.psddev.cms.db;

import java.util.ArrayList;
import java.util.List;

import com.psddev.dari.db.Record;

@AbVariationField.Embedded
public class AbVariationField extends Record {

    private List<AbVariation> variants;

    public List<AbVariation> getVariations() {
        if (variants == null) {
            variants = new ArrayList<AbVariation>();
        }
        return variants;
    }

    public void setVariations(List<AbVariation> variants) {
        this.variants = variants;
    }
}
