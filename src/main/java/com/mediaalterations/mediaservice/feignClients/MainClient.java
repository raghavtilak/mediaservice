package com.mediaalterations.mediaservice.feignClients;

import com.mediaalterations.mediaservice.dto.ProcessStatus;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(name = "main-service", path = "/process", url = "${services.main-service.url}")
public interface MainClient {

    /*
     * 
     * HttpURLConnection class does not recognize the PATCH method as a valid type
     * To resolve this, you must explicitly configure your Feign client to use a
     * different HTTP client, such as Apache HttpClient 5 or OkHttp, which both
     * support the PATCH method.
     * 
     */

    @PutMapping("/updateStatus/{status}/{fileSize}/{fileDuration}/{id}")
    ResponseEntity<String> updateStatusForProcess(
            @PathVariable("status") ProcessStatus status,
            @PathVariable("fileSize") String fileSize,
            @PathVariable("fileDuration") String fileDuration,
            @PathVariable("id") String processId);
}
