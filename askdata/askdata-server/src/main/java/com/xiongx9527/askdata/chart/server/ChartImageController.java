package com.xiongx9527.askdata.chart.server;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side image HTTP endpoint (acceptance A4): returns the PNG bytes for a token.
 * Works together with ChartImageService's in-memory store so the imageUrl returned to
 * MCP / non-interactive / export consumers is directly accessible.
 */
@RestController
@RequestMapping("/api/chart/image")
public class ChartImageController {

    private final ChartImageService chartImageService;

    public ChartImageController(ChartImageService chartImageService) {
        this.chartImageService = chartImageService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> image(@PathVariable("token") String token) {
        byte[] png = chartImageService.get(token);
        if (png == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new byte[0]);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(png, headers, HttpStatus.OK);
    }
}
