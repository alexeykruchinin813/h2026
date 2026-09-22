package ru.dit.heattracer.model;

/**
 * Одна строка таблицы 1 ТЗ — параметры условного диаметра.
 */
public class DiameterSpec {

    private final int diameter;          // ДУ, мм
    private final double capacityTph;    // пропускная способность, т/ч
    private final double maxLengthM;     // предельная длина, м
    private final long costPerM;         // стоимость нового строительства, руб./м
    private final double pairWidthM;     // расчётная ширина пары труб, м
    private final double heightM;        // расчётная высота, м

    public DiameterSpec(int diameter, double capacityTph, double maxLengthM,
                        long costPerM, double pairWidthM, double heightM) {
        this.diameter = diameter;
        this.capacityTph = capacityTph;
        this.maxLengthM = maxLengthM;
        this.costPerM = costPerM;
        this.pairWidthM = pairWidthM;
        this.heightM = heightM;
    }

    public int getDiameter()        { return diameter; }
    public double getCapacityTph()  { return capacityTph; }
    public double getMaxLengthM()   { return maxLengthM; }
    public long getCostPerM()       { return costPerM; }
    public double getPairWidthM()   { return pairWidthM; }
    public double getHeightM()      { return heightM; }

    @Override
    public String toString() {
        return "DU" + diameter
                + "{cap=" + capacityTph + " т/ч"
                + ", maxL=" + maxLengthM + " м"
                + ", cost=" + costPerM + " руб/м"
                + ", w=" + pairWidthM + " м"
                + ", h=" + heightM + " м}";
    }
}