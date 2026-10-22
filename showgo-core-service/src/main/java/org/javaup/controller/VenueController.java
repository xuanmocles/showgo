package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.entity.Venue;
import org.javaup.service.IVenueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场馆接口：城市筛选、详情、附近场馆（Redis GEO）。
 */
@RestController
@RequestMapping("/venue")
public class VenueController {

    @Resource
    private IVenueService venueService;

    @GetMapping("/cities")
    public Result<List<String>> cities() {
        return Result.ok(venueService.listCities());
    }

    @GetMapping("/nearby")
    public Result<List<Venue>> nearby(@RequestParam("x") double x,
                                      @RequestParam("y") double y,
                                      @RequestParam(value = "page", defaultValue = "1") int page) {
        return Result.ok(venueService.queryNearby(x, y, page));
    }

    @GetMapping("/{id}")
    public Result<Venue> detail(@PathVariable("id") Long id) {
        return Result.ok(venueService.queryById(id));
    }
}
