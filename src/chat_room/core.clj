(ns chat-room.core
  (:require [aleph.http :as http]
            [manifold.bus :as bus]
            [manifold.stream :as s]
            [clojure.data.json :as json]))

;; 配置
(def chat-bus (bus/event-bus))
(def active-connections (atom {}))

;; 广播用户列表
(defn broadcast-users [room-id]
  (let [users (keys (get @active-connections (keyword room-id) {}))
        message (json/write-str {:type "users" :users users})]
    (println (str "Broadcasting user list for room " room-id ": " users))
    (bus/publish! chat-bus (keyword room-id) message)))

;; WebSocket 处理
(defn chat-handler [req]
  (let [ws-conn @(http/websocket-connection req)
        user-id (str (java.util.UUID/randomUUID))
        room-id (get-in req [:query-params :room] "default")]
    (println (str "New connection: user-id=" user-id ", room-id=" room-id))
    ;; 注册连接
    (swap! active-connections update (keyword room-id)
           (fn [m] (assoc (or m {}) user-id ws-conn)))
    (broadcast-users room-id)
    ;; 发送加入通知
    (let [join-msg (json/write-str
                     {:user-id "System"
                      :message (str user-id " joined")
                      :type "system"
                      :timestamp (str (java.time.Instant/now))
                      :room-id room-id})]
      (println (str "Sending join message: " join-msg))
      (bus/publish! chat-bus (keyword room-id) join-msg))
    ;; 清理连接
    (s/on-closed ws-conn
      (fn []
        (println (str "Connection closed: user-id=" user-id ", room-id=" room-id))
        (swap! active-connections update (keyword room-id) dissoc user-id)
        (let [leave-msg (json/write-str
                          {:user-id "System"
                           :message (str user-id " left")
                           :type "system"
                           :timestamp (str (java.time.Instant/now))
                           :room-id room-id})]
          (println (str "Sending leave message: " leave-msg))
          (bus/publish! chat-bus (keyword room-id) leave-msg)
          (broadcast-users room-id))))
    ;; 订阅聊天室
    (s/connect (bus/subscribe chat-bus (keyword room-id)) ws-conn)
    ;; 发布消息
  (s/consume
    (fn [msg]
      (try
        (let [parsed (json/read-str msg :key-fn keyword)
              message-type (or (:type parsed) "user")
              message (json/write-str
                        {:user-id user-id
                         :message (:message parsed)
                         :type message-type
                         :timestamp (str (java.time.Instant/now))
                         :room-id room-id})]
          (println (str "Received message from user-id=" user-id ": " msg))
          (bus/publish! chat-bus (keyword room-id) message)) ; Publish directly
        (catch Exception e
          (println (str "Error parsing message from user-id=" user-id ": " (.getMessage e))))))
    ws-conn)))
    
;; 启动服务器
(defn -main []
  (println "Starting chat server on port 8080")
  (http/start-server chat-handler {:port 8080})
  (println "Chat server running on http://vajra.one:8080"))

;; 运行：(comment (-main))
