package ru.dit.heattracer.validator;

import java.util.ArrayList;
import java.util.List;

public class ValidationReport {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private long totalFeatures = 0;

    public void addError(String msg)   { errors.add(msg); }
    public void addWarning(String msg) { warnings.add(msg); }
    public void setTotalFeatures(long n) { this.totalFeatures = n; }

    public List<String> getErrors()   { return errors; }
    public List<String> getWarnings() { return warnings; }
    public long getTotalFeatures()    { return totalFeatures; }

    public boolean hasErrors()   { return !errors.isEmpty(); }
    public boolean hasWarnings() { return !warnings.isEmpty(); }
    public boolean isClean()     { return errors.isEmpty() && warnings.isEmpty(); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ValidationReport{total=").append(totalFeatures)
                .append(", errors=").append(errors.size())
                .append(", warnings=").append(warnings.size()).append("}");
        if (!errors.isEmpty()) {
            sb.append("\n  ERRORS:");
            for (String e : errors) sb.append("\n    - ").append(e);
        }
        if (!warnings.isEmpty()) {
            sb.append("\n  WARNINGS:");
            for (String w : warnings) sb.append("\n    - ").append(w);
        }
        return sb.toString();
    }
}