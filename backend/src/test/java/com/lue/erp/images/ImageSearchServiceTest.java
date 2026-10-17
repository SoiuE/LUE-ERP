package com.lue.erp.images;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSearchServiceTest {
    @Test
    void filtersLowSimilarityAndOrdersAcceptedHits() {
        List<ImageSearchClient.Hit> hits = List.of(
            new ImageSearchClient.Hit(1, 0.19),
            new ImageSearchClient.Hit(2, 0.72),
            new ImageSearchClient.Hit(3, 0.41)
        );

        List<ImageSearchClient.Hit> accepted = ImageSearchService.acceptedHits(hits, 0.20);

        assertEquals(List.of(2L, 3L), accepted.stream().map(ImageSearchClient.Hit::assetId).toList());
    }

    @Test
    void mergesMultipleImagesForOneObjectAndCapsAtTopTen() {
        List<Map<String,Object>> rows = new ArrayList<>();
        Map<Long,Double> scores = new LinkedHashMap<>();
        for (long id = 1; id <= 12; id++) {
            rows.add(row(id, "SKU", id));
            scores.put(id, 1.0 - id / 100.0);
        }
        rows.add(row(99, "SKU", 1));
        scores.put(99L, 0.995);

        List<Map<String,Object>> result = ImageSearchService.mergeObjectRows(rows, scores);

        assertEquals(10, result.size());
        assertEquals(99L, ((Number) result.get(0).get("assetId")).longValue());
        assertEquals(1L, result.stream().filter(item -> "SKU:1".equals(item.get("objectType") + ":" + item.get("objectId"))).count());
        assertTrue(((Number) result.get(0).get("score")).doubleValue() > ((Number) result.get(1).get("score")).doubleValue());
    }

    @Test
    void handlesEmptyIndexResults() {
        assertTrue(ImageSearchService.acceptedHits(List.of(), 0.20).isEmpty());
        assertTrue(ImageSearchService.mergeObjectRows(List.of(), Map.of()).isEmpty());
    }

    private static Map<String,Object> row(long assetId, String objectType, long objectId) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("assetId", assetId);
        row.put("objectType", objectType);
        row.put("objectId", objectId);
        return row;
    }
}
