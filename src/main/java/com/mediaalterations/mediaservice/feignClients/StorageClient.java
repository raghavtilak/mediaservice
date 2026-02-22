package com.mediaalterations.mediaservice.feignClients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "storage-service", path = "/storage", url = "${services.storage-service.url}")
public interface StorageClient {
    @GetMapping("/makeFileDownloadable/{storageId}")
    public ResponseEntity<String> makeFileDownloadable(
            @PathVariable("filename") String storageId);
}
