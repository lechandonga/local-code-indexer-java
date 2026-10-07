package com.github.lechandonga.localcodeindexerjava.web;

import com.github.lechandonga.localcodeindexerjava.indexer.IndexManager;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerErrorCode;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerException;
import com.github.lechandonga.localcodeindexerjava.indexer.RefreshStatus;
import com.github.lechandonga.localcodeindexerjava.indexer.SearchResult;
import com.github.lechandonga.localcodeindexerjava.indexer.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地代码索引 HTTP 接口，仅绑定本机使用场景。
 *
 * <ul>
 *   <li>POST /api/index/refresh?wait=true|false 触发/复用刷新；</li>
 *   <li>GET  /api/index/status 查询刷新状态与统计；</li>
 *   <li>GET  /api/search?q=&mode=keyword|symbol&limit= 检索代码。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class IndexerController {

    private final IndexManager indexManager;
    private final SearchService searchService;

    public IndexerController(IndexManager indexManager, SearchService searchService) {
        this.indexManager = indexManager;
        this.searchService = searchService;
    }

    @PostMapping("/index/refresh")
    public RefreshStatus refresh(@RequestParam(name = "wait", defaultValue = "true") boolean wait) {
        return indexManager.triggerRefresh(wait);
    }

    @GetMapping("/index/status")
    public RefreshStatus status() {
        return indexManager.statusView(false);
    }

    @GetMapping("/search")
    public SearchResult search(@RequestParam(name = "q", required = false) String query,
                               @RequestParam(name = "mode", required = false) String mode,
                               @RequestParam(name = "limit", required = false) Integer limit) {
        IndexSnapshot snapshot = indexManager.currentSnapshot();
        if (snapshot == null) {
            throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT,
                    "索引尚未建立，请先调用 POST /api/index/refresh");
        }
        return searchService.search(snapshot, query, mode, limit);
    }
}
