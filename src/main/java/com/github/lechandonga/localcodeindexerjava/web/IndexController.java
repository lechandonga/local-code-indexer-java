package com.github.lechandonga.localcodeindexerjava.web;

import com.github.lechandonga.localcodeindexerjava.index.IndexService;
import com.github.lechandonga.localcodeindexerjava.index.RefreshOutcome;
import com.github.lechandonga.localcodeindexerjava.index.RefreshStatus;
import com.github.lechandonga.localcodeindexerjava.search.SearchResult;
import com.github.lechandonga.localcodeindexerjava.search.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地代码索引 HTTP 接口，仅绑定本机使用场景，不依赖任何外部服务。
 *
 * <ul>
 *   <li>POST /api/index/refresh?wait=true|false&amp;reuse=true|false 触发刷新</li>
 *   <li>GET  /api/index/status 查询最近一次/进行中的刷新状态</li>
 *   <li>GET  /api/index/skipped 查询最近快照中被跳过的文件及原因</li>
 *   <li>GET  /api/search?q=&amp;mode=KEYWORD|SYMBOL 检索代码</li>
 * </ul>
 */
@RestController
public class IndexController {

    private final IndexService indexService;
    private final SearchService searchService;

    public IndexController(IndexService indexService, SearchService searchService) {
        this.indexService = indexService;
        this.searchService = searchService;
    }

    @PostMapping("/api/index/refresh")
    public ResponseEntity<?> refresh(@RequestParam(defaultValue = "true") boolean wait,
                                     @RequestParam(defaultValue = "true") boolean reuse) {
        if (wait) {
            RefreshOutcome outcome = indexService.triggerRefreshWithOutcome(reuse);
            return ResponseEntity.ok(wrapStatus(outcome.status(), outcome.reused()));
        }
        boolean reused = reuse && indexService.isRefreshRunning();
        if (!reused) {
            indexService.triggerRefresh(false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("reused", reused);
        body.put("status", indexService.currentStatus());
        return reused ? ResponseEntity.ok(body) : ResponseEntity.accepted().body(body);
    }

    @GetMapping("/api/index/status")
    public RefreshStatus status() {
        return indexService.currentStatus();
    }

    @GetMapping("/api/index/skipped")
    public Map<String, Object> skipped() {
        var snapshot = indexService.currentSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", snapshot.revision());
        body.put("indexedCount", snapshot.indexedCount());
        body.put("skippedCount", snapshot.skippedCount());
        body.put("skippedFiles", snapshot.skippedFiles());
        return body;
    }

    @GetMapping("/api/search")
    public SearchResult search(@RequestParam(name = "q", required = false) String query,
                               @RequestParam(name = "mode", required = false) String mode,
                               @RequestParam(name = "caseSensitive", required = false)
                               Boolean caseSensitive,
                               @RequestParam(name = "limit", required = false) Integer limit) {
        return searchService.search(query, mode, caseSensitive, limit);
    }

    private Map<String, Object> wrapStatus(RefreshStatus status, boolean reused) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("reused", reused);
        body.put("status", status);
        return body;
    }
}
