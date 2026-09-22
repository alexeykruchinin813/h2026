package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.DiameterSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Подбор условного диаметра по таблице 1 ТЗ.
 *
 * Логика по разделу 2.3 ТЗ:
 *  - Сначала выбирается минимальный ДУ, пропускная способность которого >= расхода.
 *  - Если этот ДУ не проходит по предельной длине, берётся следующий,
 *    удовлетворяющий обоим условиям (расход + предельная длина).
 *  - Произвольное завышение ДУ не допускается.
 *  - ДУ не должен уменьшаться по направлению к точке присоединения
 *    (обеспечивается вызывающим кодом через {@link #pickAtLeast}).
 */
@Service
public class DiameterPicker {

    private static final Logger log = LoggerFactory.getLogger(DiameterPicker.class);

    /**
     * Таблица 1 ТЗ. Порядок по возрастанию ДУ.
     */
    private static final DiameterSpec[] TABLE = new DiameterSpec[] {
            new DiameterSpec(  50,     3.5,   181,   74_023L, 0.400, 0.125),
            new DiameterSpec(  65,     8.3,   245,   78_631L, 0.430, 0.140),
            new DiameterSpec(  80,    13.2,   327,   83_530L, 0.470, 0.160),
            new DiameterSpec( 100,    22.3,   419,   89_748L, 0.510, 0.180),
            new DiameterSpec( 125,    40.2,   554,   97_275L, 0.600, 0.225),
            new DiameterSpec( 150,    65.1,   696,  105_507L, 0.650, 0.250),
            new DiameterSpec( 200,   152.3,  1042,  120_275L, 0.880, 0.315),
            new DiameterSpec( 250,   274.9,  1379,  135_323L, 1.050, 0.400),
            new DiameterSpec( 300,   437.4,  1718,  150_022L, 1.150, 0.450),
            new DiameterSpec( 400,   943.1,  2477,  190_299L, 1.370, 0.560),
            new DiameterSpec( 500,  1663.4,  3245,  224_137L, 1.670, 0.710),
            new DiameterSpec( 600,  2627.7,  4037,  264_790L, 1.850, 0.800),
            new DiameterSpec( 700,  3735.1,  4775,  324_298L, 2.050, 0.900),
            new DiameterSpec( 800,  5296.8,  5644,  325_996L, 2.250, 1.000),
            new DiameterSpec( 900,  7165.0,  6518,  327_693L, 2.450, 1.100),
            new DiameterSpec(1000,  9391.8,  7419,  418_777L, 2.650, 1.200),
            new DiameterSpec(1200, 15012.8,  9288,  428_074L, 3.100, 1.425),
            new DiameterSpec(1400, 22501.9, 11276,  683_417L, 3.450, 1.600),
    };

    // ============================================================
    // Публичные методы
    // ============================================================

    /**
     * Минимальный ДУ, пропускная способность которого >= расхода.
     * Используется для предварительной оценки ДУ кластера.
     *
     * @throws IllegalArgumentException если расход больше максимальной пропускной способности
     */
    public DiameterSpec pickForFlow(double flowTph) {
        if (flowTph < 0) {
            throw new IllegalArgumentException("Расход не может быть отрицательным: " + flowTph);
        }
        for (DiameterSpec spec : TABLE) {
            if (spec.getCapacityTph() >= flowTph) {
                return spec;
            }
        }
        throw new IllegalArgumentException(
                "Расход " + flowTph + " т/ч превышает максимальную пропускную способность "
                        + TABLE[TABLE.length - 1].getCapacityTph() + " т/ч (ДУ 1400)");
    }

    /**
     * Минимальный ДУ, удовлетворяющий одновременно расходу и предельной длине.
     * Основной метод для E2/E3/E7.
     *
     * @param flowTph  расчётный расход, т/ч
     * @param lengthM  длина непрерывного участка одного ДУ, м
     */
    public DiameterSpec pickForFlowAndLength(double flowTph, double lengthM) {
        return pickAtLeast(flowTph, lengthM, 0);
    }

    /**
     * То же, что pickForFlowAndLength, но ДУ не меньше minDiameter.
     * Используется для правила «ДУ не уменьшается по направлению к точке присоединения».
     *
     * @param flowTph      расчётный расход, т/ч
     * @param lengthM      длина непрерывного участка одного ДУ, м
     * @param minDiameter  минимальный допустимый ДУ (0 = без ограничения)
     */
    public DiameterSpec pickAtLeast(double flowTph, double lengthM, int minDiameter) {
        if (flowTph < 0) {
            throw new IllegalArgumentException("Расход не может быть отрицательным: " + flowTph);
        }
        if (lengthM < 0) {
            throw new IllegalArgumentException("Длина не может быть отрицательной: " + lengthM);
        }
        for (DiameterSpec spec : TABLE) {
            if (spec.getDiameter() < minDiameter) {
                continue;
            }
            if (spec.getCapacityTph() >= flowTph && spec.getMaxLengthM() >= lengthM) {
                return spec;
            }
        }
        throw new IllegalArgumentException(
                "Не удаётся подобрать ДУ: расход=" + flowTph + " т/ч, длина=" + lengthM
                        + " м, minDiameter=" + minDiameter);
    }

    /**
     * Получить параметры ДУ по его значению.
     * Используется в E7 (стоимость), F (габариты) и отладке.
     *
     * @return Optional.empty(), если ДУ нет в таблице
     */
    public Optional<DiameterSpec> getSpec(int diameter) {
        for (DiameterSpec spec : TABLE) {
            if (spec.getDiameter() == diameter) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /**
     * Все записи таблицы 1 — по возрастанию ДУ.
     */
    public List<DiameterSpec> all() {
        List<DiameterSpec> out = new ArrayList<>(TABLE.length);
        for (DiameterSpec spec : TABLE) {
            out.add(spec);
        }
        return out;
    }

    /**
     * Для отладки: сколько записей в таблице (должно быть 18).
     */
    public int size() {
        return TABLE.length;
    }
}