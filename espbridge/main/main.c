/*
 * InfiCam ESP32-S3 Web Control bridge
 *
 * Wi-Fi side: local SoftAP used by the Android phone.
 * USB side: CDC-NCM Ethernet with a fixed address and DHCP for the PC.
 * Data path: transparent TCP forwarding only; image/video data is never decoded.
 */

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "esp_check.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "nvs_flash.h"

#include "dhcpserver/dhcpserver_options.h"
#include "dhcpserver/dhcpserver.h"
#include "lwip/esp_netif_net_stack.h"
#include "lwip/inet.h"
#include "lwip/ip4_addr.h"
#include "lwip/sockets.h"

#include "tinyusb.h"
#include "tinyusb_default_config.h"
#include "tinyusb_net.h"

#define BRIDGE_WIFI_SSID       "InfiCamBridge"
#define BRIDGE_WIFI_PASSWORD   "5KfHSF21"
#define REGISTRATION_PORT      7777
#define PUBLIC_HTTP_PORT       80
#define MAX_PROXY_CONNECTIONS  8
#define PROXY_BUFFER_SIZE      8192

#define WIFI_IP_A 192
#define WIFI_IP_B 168
#define WIFI_IP_C 8
#define WIFI_IP_D 1

#define USB_IP_A 192
#define USB_IP_B 168
#define USB_IP_C 7
#define USB_IP_D 1

static const char *TAG = "inficam_bridge";
static esp_netif_t *s_usb_netif;
static SemaphoreHandle_t s_registration_lock;
static SemaphoreHandle_t s_proxy_slots;
static uint32_t s_phone_address;
static uint16_t s_phone_port;
static uint32_t s_registration_generation;
static int s_registration_socket = -1;

static bool is_wifi_peer(uint32_t address)
{
    const uint32_t host = ntohl(address);
    return (host & 0xffffff00U) ==
           (((uint32_t)WIFI_IP_A << 24) | ((uint32_t)WIFI_IP_B << 16) |
            ((uint32_t)WIFI_IP_C << 8));
}

static void configure_socket(int socket_fd)
{
    const int yes = 1;
    const struct timeval timeout = { .tv_sec = 5, .tv_usec = 0 };
    setsockopt(socket_fd, SOL_SOCKET, SO_KEEPALIVE, &yes, sizeof(yes));
    setsockopt(socket_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}

static int send_all(int socket_fd, const void *data, size_t length)
{
    const uint8_t *cursor = data;
    while (length != 0) {
        const ssize_t sent = send(socket_fd, cursor, length, 0);
        if (sent > 0) {
            cursor += sent;
            length -= (size_t)sent;
            continue;
        }
        if (sent < 0 && errno == EINTR) {
            continue;
        }
        return -1;
    }
    return 0;
}

static int receive_line(int socket_fd, char *line, size_t capacity)
{
    size_t used = 0;
    while (used + 1 < capacity) {
        char value;
        const ssize_t received = recv(socket_fd, &value, 1, 0);
        if (received == 0) {
            return 0;
        }
        if (received < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (value == '\n') {
            line[used] = 0;
            return (int)used;
        }
        if (value != '\r') {
            line[used++] = value;
        }
    }
    line[capacity - 1] = 0;
    return -1;
}

static void registration_task(void *argument)
{
    const int socket_fd = (int)(intptr_t)argument;
    struct sockaddr_in peer = { 0 };
    socklen_t peer_length = sizeof(peer);
    char line[96];
    uint32_t generation = 0;

    configure_socket(socket_fd);
    const struct timeval receive_timeout = { .tv_sec = 8, .tv_usec = 0 };
    setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO,
               &receive_timeout, sizeof(receive_timeout));

    if (getpeername(socket_fd, (struct sockaddr *)&peer, &peer_length) != 0 ||
        peer.sin_family != AF_INET || !is_wifi_peer(peer.sin_addr.s_addr)) {
        ESP_LOGW(TAG, "Rejected registration from a non-SoftAP peer");
        goto done;
    }

    if (receive_line(socket_fd, line, sizeof(line)) <= 0) {
        goto done;
    }

    unsigned protocol_version = 0;
    unsigned web_port = 0;
    if (sscanf(line, "REGISTER %u %u", &protocol_version, &web_port) != 2 ||
        protocol_version != 1 || web_port == 0 || web_port > 65535) {
        static const char error_response[] = "ERROR invalid registration\n";
        send_all(socket_fd, error_response, sizeof(error_response) - 1);
        goto done;
    }

    int old_socket = -1;
    xSemaphoreTake(s_registration_lock, portMAX_DELAY);
    old_socket = s_registration_socket;
    s_registration_socket = socket_fd;
    s_phone_address = peer.sin_addr.s_addr;
    s_phone_port = (uint16_t)web_port;
    generation = ++s_registration_generation;
    xSemaphoreGive(s_registration_lock);
    if (old_socket >= 0 && old_socket != socket_fd) {
        shutdown(old_socket, SHUT_RDWR);
    }

    char address[INET_ADDRSTRLEN];
    inet_ntoa_r(peer.sin_addr, address, sizeof(address));
    ESP_LOGI(TAG, "InfiCam registered at %s:%u", address, web_port);
    static const char ok_response[] = "OK http://192.168.7.1/\n";
    if (send_all(socket_fd, ok_response, sizeof(ok_response) - 1) != 0) {
        goto done;
    }

    while (receive_line(socket_fd, line, sizeof(line)) > 0) {
        if (strcmp(line, "PING") != 0 ||
            send_all(socket_fd, "PONG\n", 5) != 0) {
            break;
        }
    }

done:
    xSemaphoreTake(s_registration_lock, portMAX_DELAY);
    if (generation != 0 && generation == s_registration_generation &&
        s_registration_socket == socket_fd) {
        s_registration_socket = -1;
        s_phone_address = 0;
        s_phone_port = 0;
        ++s_registration_generation;
        ESP_LOGW(TAG, "InfiCam registration lost");
    }
    xSemaphoreGive(s_registration_lock);
    shutdown(socket_fd, SHUT_RDWR);
    close(socket_fd);
    vTaskDelete(NULL);
}

static void registration_listener_task(void *argument)
{
    (void)argument;
    for (;;) {
        int listener = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
        if (listener < 0) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }
        const int yes = 1;
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        struct sockaddr_in address = {
            .sin_family = AF_INET,
            .sin_port = htons(REGISTRATION_PORT),
        };
        /* Registration is a phone-side control service; do not expose it on USB. */
        IP4_ADDR((ip4_addr_t *)&address.sin_addr,
                 WIFI_IP_A, WIFI_IP_B, WIFI_IP_C, WIFI_IP_D);
        if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0 ||
            listen(listener, 2) != 0) {
            ESP_LOGE(TAG, "Registration listener failed: errno %d", errno);
            close(listener);
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }
        ESP_LOGI(TAG, "Registration service listening on port %d", REGISTRATION_PORT);
        for (;;) {
            int client = accept(listener, NULL, NULL);
            if (client < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (xTaskCreate(registration_task, "phone_register", 4096,
                            (void *)(intptr_t)client, 5, NULL) != pdPASS) {
                close(client);
            }
        }
        close(listener);
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

static bool get_registered_phone(struct sockaddr_in *target, uint32_t *generation)
{
    bool available;
    xSemaphoreTake(s_registration_lock, portMAX_DELAY);
    available = s_phone_address != 0 && s_phone_port != 0;
    if (available) {
        memset(target, 0, sizeof(*target));
        target->sin_family = AF_INET;
        target->sin_addr.s_addr = s_phone_address;
        target->sin_port = htons(s_phone_port);
    }
    *generation = s_registration_generation;
    xSemaphoreGive(s_registration_lock);
    return available;
}

static bool registration_is_current(uint32_t generation)
{
    bool current;
    xSemaphoreTake(s_registration_lock, portMAX_DELAY);
    current = s_phone_address != 0 && generation == s_registration_generation;
    xSemaphoreGive(s_registration_lock);
    return current;
}

static void send_unavailable(int client)
{
    static const char response[] =
        "HTTP/1.1 503 Service Unavailable\r\n"
        "Content-Type: text/plain; charset=utf-8\r\n"
        "Cache-Control: no-store\r\n"
        "Connection: close\r\n\r\n"
        "Waiting for the InfiCam phone connection.\n";
    /* Consume the already-sent HTTP request before closing. Closing a TCP socket with unread
     * receive data produces an RST on Linux, which makes browsers/curl discard a valid 503. */
    const struct timeval receive_timeout = { .tv_sec = 0, .tv_usec = 250000 };
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    char request[1024];
    (void)recv(client, request, sizeof(request), 0);
    send_all(client, response, sizeof(response) - 1);
}

static void close_gracefully(int socket_fd)
{
    if (socket_fd < 0) {
        return;
    }
    /* Let the peer observe the complete HTTP response/stream before releasing the PCB. */
    shutdown(socket_fd, SHUT_WR);
    const struct timeval receive_timeout = { .tv_sec = 0, .tv_usec = 250000 };
    setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO,
               &receive_timeout, sizeof(receive_timeout));
    uint8_t discard[128];
    while (recv(socket_fd, discard, sizeof(discard), 0) > 0) {
    }
    close(socket_fd);
}

static void proxy_connection_task(void *argument)
{
    const int client = (int)(intptr_t)argument;
    int phone = -1;
    uint8_t *buffer = NULL;
    struct sockaddr_in target;
    uint32_t generation;

    configure_socket(client);
    if (!get_registered_phone(&target, &generation)) {
        send_unavailable(client);
        goto done;
    }

    phone = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (phone < 0) {
        send_unavailable(client);
        goto done;
    }
    configure_socket(phone);
    if (connect(phone, (struct sockaddr *)&target, sizeof(target)) != 0) {
        ESP_LOGW(TAG, "Cannot connect to registered phone: errno %d", errno);
        send_unavailable(client);
        goto done;
    }

    buffer = malloc(PROXY_BUFFER_SIZE);
    if (buffer == NULL) {
        goto done;
    }

    for (;;) {
        fd_set read_set;
        FD_ZERO(&read_set);
        FD_SET(client, &read_set);
        FD_SET(phone, &read_set);
        const int maximum = client > phone ? client : phone;
        struct timeval timeout = { .tv_sec = 1, .tv_usec = 0 };
        int ready = select(maximum + 1, &read_set, NULL, NULL, &timeout);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (!registration_is_current(generation)) {
            break;
        }
        if (ready == 0) {
            continue;
        }
        if (FD_ISSET(client, &read_set)) {
            const ssize_t length = recv(client, buffer, PROXY_BUFFER_SIZE, 0);
            if (length <= 0 || send_all(phone, buffer, (size_t)length) != 0) {
                break;
            }
        }
        if (FD_ISSET(phone, &read_set)) {
            const ssize_t length = recv(phone, buffer, PROXY_BUFFER_SIZE, 0);
            if (length <= 0 || send_all(client, buffer, (size_t)length) != 0) {
                break;
            }
        }
    }

done:
    free(buffer);
    if (phone >= 0) {
        shutdown(phone, SHUT_RDWR);
        close(phone);
    }
    close_gracefully(client);
    xSemaphoreGive(s_proxy_slots);
    vTaskDelete(NULL);
}

static void proxy_listener_task(void *argument)
{
    (void)argument;
    for (;;) {
        int listener = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
        if (listener < 0) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }
        const int yes = 1;
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        struct sockaddr_in address = {
            .sin_family = AF_INET,
            .sin_port = htons(PUBLIC_HTTP_PORT),
        };
        IP4_ADDR((ip4_addr_t *)&address.sin_addr, USB_IP_A, USB_IP_B, USB_IP_C, USB_IP_D);
        if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0 ||
            listen(listener, MAX_PROXY_CONNECTIONS) != 0) {
            ESP_LOGE(TAG, "HTTP proxy listener failed: errno %d", errno);
            close(listener);
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }
        ESP_LOGI(TAG, "Web Control proxy available at http://192.168.7.1");
        for (;;) {
            int client = accept(listener, NULL, NULL);
            if (client < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (xSemaphoreTake(s_proxy_slots, 0) != pdTRUE) {
                send_unavailable(client);
                close(client);
                continue;
            }
            if (xTaskCreate(proxy_connection_task, "tcp_proxy", 5120,
                            (void *)(intptr_t)client, 4, NULL) != pdPASS) {
                close(client);
                xSemaphoreGive(s_proxy_slots);
            }
        }
        close(listener);
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

static void usb_receive_buffer_free(void *handle, void *buffer)
{
    (void)handle;
    free(buffer);
}

static esp_err_t usb_transmit(void *handle, void *buffer, size_t length)
{
    (void)handle;
    return tinyusb_net_send_sync(buffer, (uint16_t)length, NULL, pdMS_TO_TICKS(100));
}

static esp_err_t usb_receive(void *buffer, uint16_t length, void *context)
{
    (void)context;
    if (s_usb_netif == NULL) {
        return ESP_OK;
    }
    void *copy = malloc(length);
    if (copy == NULL) {
        return ESP_ERR_NO_MEM;
    }
    memcpy(copy, buffer, length);
    return esp_netif_receive(s_usb_netif, copy, length, NULL);
}

static esp_err_t initialize_usb_network(void)
{
    const tinyusb_config_t usb_config = TINYUSB_DEFAULT_CONFIG();
    ESP_RETURN_ON_ERROR(tinyusb_driver_install(&usb_config), TAG,
                        "TinyUSB driver installation failed");

    const tinyusb_net_config_t ncm_config = {
        .mac_addr = { 0x02, 0x02, 0x49, 0x43, 0x50, 0x01 },
        .on_recv_callback = usb_receive,
    };
    ESP_RETURN_ON_ERROR(tinyusb_net_init(&ncm_config), TAG,
                        "TinyUSB NCM initialization failed");

    static esp_netif_ip_info_t usb_ip;
    IP4_ADDR(&usb_ip.ip, USB_IP_A, USB_IP_B, USB_IP_C, USB_IP_D);
    IP4_ADDR(&usb_ip.gw, USB_IP_A, USB_IP_B, USB_IP_C, USB_IP_D);
    IP4_ADDR(&usb_ip.netmask, 255, 255, 255, 0);

    esp_netif_inherent_config_t base_config = {
        .flags = ESP_NETIF_DHCP_SERVER | ESP_NETIF_FLAG_AUTOUP,
        .ip_info = &usb_ip,
        .if_key = "USB_NCM",
        .if_desc = "InfiCam USB NCM",
        .route_prio = 5,
    };
    const esp_netif_driver_ifconfig_t driver_config = {
        .handle = (void *)1,
        .transmit = usb_transmit,
        .driver_free_rx_buffer = usb_receive_buffer_free,
    };
    const struct esp_netif_netstack_config stack_config = {
        .lwip = {
            .init_fn = ethernetif_init,
            .input_fn = ethernetif_input,
        },
    };
    const esp_netif_config_t netif_config = {
        .base = &base_config,
        .driver = &driver_config,
        .stack = &stack_config,
    };
    s_usb_netif = esp_netif_new(&netif_config);
    ESP_RETURN_ON_FALSE(s_usb_netif != NULL, ESP_FAIL, TAG,
                        "Unable to create USB network interface");

    uint8_t server_mac[6] = { 0x02, 0x02, 0x49, 0x43, 0x50, 0x02 };
    ESP_RETURN_ON_ERROR(esp_netif_set_mac(s_usb_netif, server_mac), TAG,
                        "Unable to set USB network MAC");
    const uint32_t minimum_lease_minutes = 1;
    ESP_ERROR_CHECK_WITHOUT_ABORT(esp_netif_dhcps_option(
        s_usb_netif, ESP_NETIF_OP_SET, IP_ADDRESS_LEASE_TIME,
        (void *)&minimum_lease_minutes, sizeof(minimum_lease_minutes)));
    /* This is an isolated control link, not an Internet gateway. Avoid installing a useless
     * default route on the PC while still assigning 192.168.7.2 automatically. */
    const uint8_t offer_router = 0;
    ESP_ERROR_CHECK_WITHOUT_ABORT(esp_netif_dhcps_option(
        s_usb_netif, ESP_NETIF_OP_SET, ROUTER_SOLICITATION_ADDRESS,
        (void *)&offer_router, sizeof(offer_router)));
    esp_netif_action_start(s_usb_netif, NULL, 0, NULL);
    return ESP_OK;
}

static esp_err_t initialize_wifi_softap(void)
{
    wifi_init_config_t initialization = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&initialization), TAG, "Wi-Fi initialization failed");
    esp_netif_t *ap = esp_netif_create_default_wifi_ap();
    ESP_RETURN_ON_FALSE(ap != NULL, ESP_FAIL, TAG, "Unable to create SoftAP interface");

    esp_err_t dhcp_status = esp_netif_dhcps_stop(ap);
    ESP_RETURN_ON_FALSE(dhcp_status == ESP_OK ||
                        dhcp_status == ESP_ERR_ESP_NETIF_DHCP_ALREADY_STOPPED,
                        dhcp_status, TAG, "Unable to stop SoftAP DHCP server");
    esp_netif_ip_info_t ip;
    IP4_ADDR(&ip.ip, WIFI_IP_A, WIFI_IP_B, WIFI_IP_C, WIFI_IP_D);
    IP4_ADDR(&ip.gw, WIFI_IP_A, WIFI_IP_B, WIFI_IP_C, WIFI_IP_D);
    IP4_ADDR(&ip.netmask, 255, 255, 255, 0);
    ESP_RETURN_ON_ERROR(esp_netif_set_ip_info(ap, &ip), TAG,
                        "Unable to configure SoftAP address");

    /* esp_netif's default pool can retain the original 192.168.4.x range when the AP address is
     * changed before its first start. Configure the complete phone-side lease pool explicitly. */
    dhcps_lease_t lease = { .enable = true };
    IP4_ADDR(&lease.start_ip, WIFI_IP_A, WIFI_IP_B, WIFI_IP_C, 2);
    IP4_ADDR(&lease.end_ip, WIFI_IP_A, WIFI_IP_B, WIFI_IP_C, 10);
    ESP_RETURN_ON_ERROR(esp_netif_dhcps_option(
        ap, ESP_NETIF_OP_SET, REQUESTED_IP_ADDRESS, &lease, sizeof(lease)), TAG,
        "Unable to configure SoftAP DHCP lease pool");

    ESP_RETURN_ON_ERROR(esp_wifi_set_storage(WIFI_STORAGE_RAM), TAG,
                        "Unable to select Wi-Fi storage");
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_AP), TAG,
                        "Unable to set SoftAP mode");
    wifi_config_t configuration = {
        .ap = {
            .ssid = BRIDGE_WIFI_SSID,
            .password = BRIDGE_WIFI_PASSWORD,
            .ssid_len = sizeof(BRIDGE_WIFI_SSID) - 1,
            .channel = 6,
            .authmode = WIFI_AUTH_WPA2_PSK,
            .max_connection = 4,
            .beacon_interval = 100,
        },
    };
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_AP, &configuration), TAG,
                        "Unable to configure SoftAP");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "Unable to start SoftAP");
    /* The default AP event handler normally starts DHCP. Start it explicitly as well so custom
     * netif addresses also work reliably across ESP-IDF versions and startup ordering. */
    dhcp_status = esp_netif_dhcps_start(ap);
    ESP_RETURN_ON_FALSE(dhcp_status == ESP_OK ||
                        dhcp_status == ESP_ERR_ESP_NETIF_DHCP_ALREADY_STARTED,
                        dhcp_status, TAG, "Unable to start SoftAP DHCP server");
    ESP_LOGI(TAG, "SoftAP %s ready at 192.168.8.1", BRIDGE_WIFI_SSID);
    return ESP_OK;
}

void app_main(void)
{
    esp_err_t status = nvs_flash_init();
    if (status == ESP_ERR_NVS_NO_FREE_PAGES || status == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        status = nvs_flash_init();
    }
    ESP_ERROR_CHECK(status);
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    s_registration_lock = xSemaphoreCreateMutex();
    s_proxy_slots = xSemaphoreCreateCounting(MAX_PROXY_CONNECTIONS,
                                             MAX_PROXY_CONNECTIONS);
    ESP_ERROR_CHECK(s_registration_lock != NULL && s_proxy_slots != NULL ?
                    ESP_OK : ESP_ERR_NO_MEM);

    ESP_ERROR_CHECK(initialize_wifi_softap());
    ESP_ERROR_CHECK(initialize_usb_network());

    BaseType_t registration_started = xTaskCreate(
        registration_listener_task, "registration", 4096, NULL, 5, NULL);
    BaseType_t proxy_started = xTaskCreate(
        proxy_listener_task, "web_proxy", 4096, NULL, 5, NULL);
    ESP_ERROR_CHECK(registration_started == pdPASS && proxy_started == pdPASS ?
                    ESP_OK : ESP_ERR_NO_MEM);
}
