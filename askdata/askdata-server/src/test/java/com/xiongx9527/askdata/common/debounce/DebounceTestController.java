package com.xiongx9527.askdata.common.debounce;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/debounce")
public class DebounceTestController {

    @PostMapping("/apply")
    @Debounce(args = {"id"})
    public Result<Void> apply(@RequestParam Long id) {
        return Result.ok();
    }

    @PostMapping("/open")
    public Result<Void> open(@RequestParam Long id) {
        return Result.ok();
    }

    @PostMapping("/apply-custom")
    @Debounce(args = {"id"}, message = "请勿重复提交")
    public Result<Void> applyCustom(@RequestParam Long id) {
        return Result.ok();
    }

    @PostMapping("/apply-window")
    @Debounce(args = {"id"}, windowMs = 500)
    public Result<Void> applyWindow(@RequestParam Long id) {
        return Result.ok();
    }
}
