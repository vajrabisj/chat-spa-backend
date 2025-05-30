(ns grok-server.core
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [wkok.openai-clojure.api :as openai]
            [clj-http.client :as client]
            [clojure.string :as str]))

(def grok-user-id "Grok")
(def xai-api-key (System/getenv "GROK_API_KEY"))
;;(def api-url "https://api.x.ai/v1/chat/completions")


(defn call-grok-api [msg]
  (println (str "Calling Grok API with message: " msg))
  (d/future
    (try
      (let [response (openai/create-chat-completion
                       {:model "grok-3-beta"
                        :messages [{:role "user" :content msg}]
                        :temperature 0.7}
                       {:api-key xai-api-key
                        :api-endpoint "https://api.x.ai/v1"})]
        (let [content (-> response :choices first :message :content)]
          (println (str "Grok API response: " content))
          content))
      (catch Exception e
        (println (str "Grok API exception: " (.getMessage e)))
        (str "Error: " (.getMessage e))))))

(defn start-grok-client []
  (println "Starting Grok client, connecting to ws://vajra.one:8080?room=default")
  (let [ws-conn @(http/websocket-client "ws://vajra.one:8080?room=default")]
    (println (str "Grok client connected: " grok-user-id))
    ;; 发送加入通知
    (let [join-msg (json/write-str
                     {:user-id "System"
                      :message (str grok-user-id " joined")
                      :type "system"
                      :timestamp (str (java.time.Instant/now))
                      :room-id "default"})]
      (println (str "Sending join message: " join-msg))
      (s/put! ws-conn join-msg))
    ;; 处理消息
    (s/consume
      (fn [msg]
        (try
          (let [parsed (json/read-str msg :key-fn keyword)]
            (println (str "Received message: " msg))
            (when (and (not= (:user-id parsed) grok-user-id)
                       (or (str/includes? (:message parsed) "@Grok")
                           (str/includes? (:message parsed) "@all")))
              (let [think-msg (json/write-str
                                {:user-id "System"
                                 :message "Grok is thinking..."
                                 :type "system"
                                 :timestamp (str (java.time.Instant/now))
                                 :room-id "default"})]
                (println (str "Sending thinking message: " think-msg))
                (when-not (s/closed? ws-conn)
                  (s/put! ws-conn think-msg)))
              (-> (call-grok-api (str/replace (:message parsed) #"@Grok|@all" ""))
                  (d/chain
                    (fn [response]
                      (let [response-msg (json/write-str
                                           {:user-id grok-user-id
                                            :message response
                                            :type "llm"
                                            :timestamp (str (java.time.Instant/now))
                                            :room-id "default"})]
                        (println (str "Sending Grok response: " response-msg))
                        (when-not (s/closed? ws-conn)
                          (s/put! ws-conn response-msg)))))
                  (d/catch
                    (fn [error]
                      (println (str "Error in API response handling: " error))
                      (when-not (s/closed? ws-conn)
                        (s/put! ws-conn
                                (json/write-str
                                  {:user-id "System"
                                   :message (str "Grok failed to respond: " error)
                                   :type "system"
                                   :timestamp (str (java.time.Instant/now))
                                   :room-id "default"}))))))))
          (catch Exception e
            (println (str "Error processing message: " (.getMessage e))))))
      ws-conn)
    ;; 清理连接
    (s/on-closed ws-conn
      (fn []
        (println (str "Grok client disconnected: " grok-user-id))
        (let [leave-msg (json/write-str
                          {:user-id "System"
                           :message (str grok-user-id " left")
                           :type "system"
                           :timestamp (str (java.time.Instant/now))
                           :room-id "default"})]
          (println (str "Sending leave message: " leave-msg))
          (s/put! ws-conn leave-msg))))
    ws-conn))

(defn -main []
  (println "Starting Grok server")
  (start-grok-client)
  (println "Grok server running")
  (Thread/sleep Long/MAX_VALUE))
