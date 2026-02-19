package com.mediaalterations.mediaservice.feignClients;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "storage-service")
public interface StorageClient {

}
