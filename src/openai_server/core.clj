(ns openai-server.core
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [wkok.openai-clojure.api :as openai]
            [clj-http.client :as client]
            [clojure.string :as str]))

(def openai-user-id "Openai")
(def xai-api-key (System/getenv "OPENAI_API_KEY"))


(defn call-openai-api [msg]
  (println (str "Calling Openai API with message: " msg))
  (d/future
    (try
      (let [response (openai/create-chat-completion
                       {:model "gpt-4.1"
                        :messages [{:role "user" :content msg}]
                        :temperature 0.7})]
        (let [content (-> response :choices first :message :content)]
          (println (str "Openai API response: " content))
          content))
      (catch Exception e
        (println (str "Openai API exception: " (.getMessage e)))
        (str "Error: " (.getMessage e))))))

(defn start-openai-client []
  (println "Starting Openai client, connecting to ws://vajra.one:8080?room=default")
  (let [ws-conn @(http/websocket-client "ws://vajra.one:8080?room=default")]
    (println (str "Openai client connected: " openai-user-id))
    ;; 发送加入通知
    (let [join-msg (json/write-str
                     {:user-id "System"
                      :message (str openai-user-id " joined")
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
            (when (and (not= (:user-id parsed) openai-user-id)
                       (or (str/includes? (:message parsed) "@Openai")
                           (str/includes? (:message parsed) "@all")))
              (let [think-msg (json/write-str
                                {:user-id "System"
                                 :message "Openai is thinking..."
                                 :type "system"
                                 :timestamp (str (java.time.Instant/now))
                                 :room-id "default"})]
                (println (str "Sending thinking message: " think-msg))
                (when-not (s/closed? ws-conn)
                  (s/put! ws-conn think-msg)))
              (-> (call-openai-api (str/replace (:message parsed) #"@Openai|@all" ""))
                  (d/chain
                    (fn [response]
                      (let [response-msg (json/write-str
                                           {:user-id openai-user-id
                                            :message response
                                            :type "llm"
                                            :timestamp (str (java.time.Instant/now))
                                            :room-id "default"})]
                        (println (str "Sending Openai response: " response-msg))
                        (when-not (s/closed? ws-conn)
                          (s/put! ws-conn response-msg)))))
                  (d/catch
                    (fn [error]
                      (println (str "Error in API response handling: " error))
                      (when-not (s/closed? ws-conn)
                        (s/put! ws-conn
                                (json/write-str
                                  {:user-id "System"
                                   :message (str "Openai failed to respond: " error)
                                   :type "system"
                                   :timestamp (str (java.time.Instant/now))
                                   :room-id "default"}))))))))
          (catch Exception e
            (println (str "Error processing message: " (.getMessage e))))))
      ws-conn)
    ;; 清理连接
    (s/on-closed ws-conn
      (fn []
        (println (str "Openai client disconnected: " openai-user-id))
        (let [leave-msg (json/write-str
                          {:user-id "System"
                           :message (str openai-user-id " left")
                           :type "system"
                           :timestamp (str (java.time.Instant/now))
                           :room-id "default"})]
          (println (str "Sending leave message: " leave-msg))
          (s/put! ws-conn leave-msg))))
    ws-conn))

(defn -main []
  (println "Starting Openai server")
  (start-openai-client)
  (println "Openai server running")
  (Thread/sleep Long/MAX_VALUE))
