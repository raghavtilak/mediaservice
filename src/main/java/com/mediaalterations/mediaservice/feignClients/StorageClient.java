package com.mediaalterations.mediaservice.feignClients;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "storage-service", path = "/storage", url = "${services.storage-service.url}")
public interface StorageClient {

}
