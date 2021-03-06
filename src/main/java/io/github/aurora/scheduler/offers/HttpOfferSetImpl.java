/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.aurora.scheduler.offers;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import javax.inject.Qualifier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.inject.Inject;

import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.filter.SchedulingFilter.ResourceRequest;
import org.apache.aurora.scheduler.offers.HostOffer;
import org.apache.aurora.scheduler.offers.OfferSet;
import org.apache.aurora.scheduler.resources.ResourceType;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Implementation for OfferSet.
 * HttpOfferSetImpl sorts offers using an external endpoint.
 * It sends the request (request + offers) to the external endpoint
 * and receives the response (sorted offers).
 */
@VisibleForTesting
public class HttpOfferSetImpl implements OfferSet {

  private static final Logger LOG = LoggerFactory.getLogger(HttpOfferSetImpl.class);
  private final Set<HostOffer> offers;
  private final Gson gson = new Gson();
  private Integer timeoutMs;
  private URL endpoint;
  private Integer maxRetries;

  public HttpOfferSetImpl(Set<HostOffer> offers) {
    this.offers = offers;
  }

  @VisibleForTesting
  @Qualifier
  @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
  @interface Endpoint { }

  @VisibleForTesting
  @Qualifier
  @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
  @interface MaxRetries { }

  @VisibleForTesting
  @Qualifier
  @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
  @interface TimeoutMs { }

  @Inject
  public HttpOfferSetImpl(Ordering<HostOffer> ordering,
                          @TimeoutMs Integer timeoutMs,
                          @Endpoint String url,
                          @MaxRetries Integer maxRetries) {
    offers = new ConcurrentSkipListSet<>(ordering);
    try {
      endpoint = new URL(url);
      HttpOfferSetModule.enable(true);
      LOG.info("HttpOfferSetModule Enabled.");
    } catch (MalformedURLException e) {
      LOG.error("http_offer_set_endpoint is malformed. ", e);
      HttpOfferSetModule.enable(false);
      LOG.info("HttpOfferSetModule Disabled.");
    }
    this.timeoutMs = timeoutMs;
    this.maxRetries = maxRetries;
    LOG.info("HttpOfferSet's endpoint: " + this.endpoint);
    LOG.info("HttpOfferSet's timeout: " + this.timeoutMs + " (ms)");
    LOG.info("HttpOfferSet's max retries: " + this.maxRetries);
  }

  @Override
  public void add(HostOffer offer) {
    offers.add(offer);
  }

  @Override
  public void remove(HostOffer removed) {
    offers.remove(removed);
  }

  @Override
  public int size() {
    // Potential gotcha - since this is a ConcurrentSkipListSet, size() is more
    // expensive.
    // Could track this separately if it turns out to pose problems.
    return offers.size();
  }

  @Override
  public void clear() {
    offers.clear();
  }

  @Override
  public Iterable<HostOffer> values() {
    return offers;
  }

  @Override
  public Iterable<HostOffer> getOrdered(TaskGroupKey groupKey, ResourceRequest resourceRequest) {
    // if there are no available offers, do nothing.
    if (offers.isEmpty() || !HttpOfferSetModule.isEnabled()) {
      return offers;
    }

    List<HostOffer> orderedOffers = null;
    try {
      long startTime = System.nanoTime();
      // create json request & send the Rest API request to the scheduler plugin
      ScheduleRequest scheduleRequest = this.createRequest(resourceRequest, startTime);
      LOG.debug("Sending request " + scheduleRequest.jobKey);
      String responseStr = this.sendRequest(scheduleRequest);
      orderedOffers = processResponse(responseStr);
      LOG.debug("received response for " + scheduleRequest.jobKey);
      HttpOfferSetModule.latencyMsList.add(System.nanoTime() - startTime);
    } catch (IOException e) {
      LOG.error("Failed to schedule the task of "
          + resourceRequest.getTask().getJob().toString()
          + " using HttpOfferSet. ", e);
      HttpOfferSetModule.incFailureCount();
    } finally {
      // shutdown HttpOfferSet if failure is consistent.
      if (HttpOfferSetModule.getFailureCount() >= maxRetries) {
        LOG.error("Reaches " + maxRetries + ". HttpOfferSetModule Disabled.");
        HttpOfferSetModule.enable(false);
      }
    }
    if (orderedOffers != null) {
      return orderedOffers;
    }

    // fall back to default scheduler.
    LOG.warn("Falling back on default ordering.");
    return offers;
  }

  //createScheduleRequest creates the ScheduleRequest to be sent out to the plugin.
  private ScheduleRequest createRequest(ResourceRequest resourceRequest, long startTime) {
    Resource req = new Resource(resourceRequest.getResourceBag().valueOf(ResourceType.CPUS),
        resourceRequest.getResourceBag().valueOf(ResourceType.RAM_MB),
        resourceRequest.getResourceBag().valueOf(ResourceType.DISK_MB));
    List<Host> hosts = offers.stream().map(offer -> new Host(offer.getAttributes().getHost(),
            new Resource(offer.getResourceBag(true).valueOf(ResourceType.CPUS)
                    + offer.getResourceBag(false).valueOf(ResourceType.CPUS),
                    offer.getResourceBag(true).valueOf(ResourceType.RAM_MB)
                            + offer.getResourceBag(false).valueOf(ResourceType.RAM_MB),
                    offer.getResourceBag(true).valueOf(ResourceType.DISK_MB)
                    + offer.getResourceBag(false).valueOf(ResourceType.DISK_MB))))
            .collect(Collectors.toList());
    IJobKey jobKey = resourceRequest.getTask().getJob();
    String jobKeyStr = jobKey.getRole() + "-" + jobKey.getEnvironment() + "-" + jobKey.getName()
            + "@" + startTime;
    return new ScheduleRequest(req, hosts, jobKeyStr);
  }

  // sendRequest sends resorceRequest to the external plugin endpoint and gets json response.
  private String sendRequest(ScheduleRequest scheduleRequest) throws IOException {
    LOG.debug("Sending request for " + scheduleRequest.toString());
    CloseableHttpClient httpClient = HttpClients.createDefault();
    try {
      HttpPost request = new HttpPost(this.endpoint.toString());
      RequestConfig requestConfig = RequestConfig.custom()
              .setConnectionRequestTimeout(this.timeoutMs)
              .setConnectTimeout(this.timeoutMs)
              .setSocketTimeout(this.timeoutMs)
              .build();
      request.setConfig(requestConfig);
      request.addHeader("Content-Type", "application/json; utf-8");
      request.addHeader("Accept", "application/json");
      request.setEntity(new StringEntity(gson.toJson(scheduleRequest)));
      CloseableHttpResponse response = httpClient.execute(request);
      try {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
          throw new IOException("Empty response from the external http endpoint.");
        } else {
          return EntityUtils.toString(entity);
        }
      } finally {
        response.close();
      }
    } finally {
      httpClient.close();
    }
  }

  List<HostOffer> processResponse(String responseStr) throws IOException {
    // process the response
    ScheduleResponse response = gson.fromJson(responseStr, ScheduleResponse.class);
    if (response.error == null || response.hosts == null) {
      LOG.error("Response: " + responseStr);
      throw new IOException("response is malformed");
    }

    Map<String, HostOffer> offerMap = offers.stream()
            .collect(Collectors.toMap(offer -> offer.getAttributes().getHost(), offer -> offer));
    List<HostOffer> orderedOffers = new ArrayList<>();
    if (response.error.trim().isEmpty()) {
      for (String host : response.hosts) {
        HostOffer offer = offerMap.get(host);
        if (offer == null) {
          LOG.warn("Cannot find host " + host + " in the response");
        } else {
          orderedOffers.add(offer);
        }
      }
      LOG.debug("Sorted offers: " + String.join(",",
          response.hosts.subList(0, Math.min(5, response.hosts.size())) + "..."));
      if (orderedOffers.isEmpty()) {
        LOG.warn("Cannot find any offers for this task. "
                + "Please check the condition of these hosts: "
                + Util.getHostnames(offers));
      }
      return orderedOffers;
    } else {
      LOG.error("Unable to get sorted offers due to " + response.error);
      throw new IOException(response.error);
    }
  }

  // Host represents for each host offer.
  static class Host {
    String name;
    Resource offer;

    Host(String mName, Resource mOffer) {
      name = mName;
      offer = mOffer;
    }

    @Override
    public String toString() {
      return "Host{" + "name='" + name + '\'' + ", offer=" + offer + '}';
    }
  }

  // Resource is used between Aurora and MagicMatch.
  static class Resource {
    double cpu;
    double memory;
    double disk;

    Resource(double cpu, double memory, double disk) {
      this.cpu = cpu;
      this.memory = memory;
      this.disk = disk;
    }

    @Override
    public String toString() {
      return "Resource{" + "cpu=" + cpu + ", memory=" + memory + ", disk=" + disk + '}';
    }
  }

  // ScheduleRequest is the request sent to MagicMatch.
  static class ScheduleRequest {
    String jobKey;
    Resource request;
    List<Host> hosts;

    ScheduleRequest(Resource request, List<Host> hosts, String jobKey) {
      this.request = request;
      this.hosts = hosts;
      this.jobKey = jobKey;
    }

    @Override
    public String toString() {
      return "ScheduleRequest{" + "jobKey=" + jobKey + "request=" + request
                + ", hosts=" + hosts + '}';
    }
  }

  // ScheduleResponse is the scheduling result responded by MagicMatch
  static class ScheduleResponse {
    String error;
    List<String> hosts;

    @Override
    public String toString() {
      return "ScheduleResponse{" + "error='" + error + '\'' + ", hosts=" + hosts + '}';
    }
  }
}
