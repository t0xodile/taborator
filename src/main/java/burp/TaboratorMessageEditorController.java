package burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.core.ByteArray;

public class TaboratorMessageEditorController {
    private HttpRequestResponse httpRequestResponse;
    private HttpRequest request;
    private HttpResponse response;
    private HttpService httpService;

    public HttpRequestResponse getHttpRequestResponse() {
        return httpRequestResponse;
    }
    
    public void setHttpRequestResponse(HttpRequestResponse httpRequestResponse) {
        this.httpRequestResponse = httpRequestResponse;
        if (httpRequestResponse != null) {
            this.request = httpRequestResponse.request();
            this.response = httpRequestResponse.response();
            this.httpService = httpRequestResponse.request().httpService();
        }
    }

    public HttpRequest getRequest() {
        return request;
    }
    
    public void setRequest(HttpRequest request) {
        this.request = request;
        if (request != null) {
            this.httpService = request.httpService();
        }
    }
    
    public void setRequest(byte[] requestBytes) {
        if (requestBytes != null) {
            this.request = HttpRequest.httpRequest(ByteArray.byteArray(requestBytes));
        }
    }
    
    public HttpResponse getResponse() {
        return response;
    }
    
    public void setResponse(HttpResponse response) {
        this.response = response;
    }
    
    public void setResponse(byte[] responseBytes) {
        if (responseBytes != null) {
            this.response = HttpResponse.httpResponse(ByteArray.byteArray(responseBytes));
        }
    }
    
    public HttpService getHttpService() {
        return httpService;
    }
    
    public void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }
}
