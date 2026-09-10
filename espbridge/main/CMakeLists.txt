set(BRIDGE_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")
if(NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/server_cert.pem" OR
   NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/server_key.pem" OR
   NOT EXISTS "${BRIDGE_ROOT}/inficam-bridge-ca.crt")
    execute_process(
        COMMAND bash "${BRIDGE_ROOT}/generate_https_certificate.sh"
        RESULT_VARIABLE CERTIFICATE_RESULT
    )
    if(NOT CERTIFICATE_RESULT EQUAL 0)
        message(FATAL_ERROR "Unable to provision the ESP bridge HTTPS certificate")
    endif()
endif()

idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS ""
    PRIV_REQUIRES esp_wifi esp_netif nvs_flash lwip esp-tls
    EMBED_TXTFILES "server_cert.pem" "server_key.pem"
)
