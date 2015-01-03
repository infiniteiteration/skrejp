(ns skrejp.system-test
  (:require [skrejp.logger :as logger])
  (:require [clojure.core.async :as async :refer [go go-loop chan <! <!! >!]])
  (:require [com.stuartsierra.component :as component])
  (:require [expectations :refer :all])
  (:require [skrejp.system :as sys])
  (:require [clojurewerkz.urly.core :as urly])
  (:require [org.httpkit.fake :refer :all]))

(def http-req-opts {:timeout    10 ; ms
                    :user-agent "User-Agent-string"
                    :headers    {"X-Header" "Value"} } )

;; # :scraper-defs
;; The strings keys refer to the host domain name. In the value the scraping rules are defined.
;;
;; - If it is a _map_ rule definitions are inside. For every attribute a selector is given. Alternatively a function.
;; - A _string_ value is taken as a reference to other definition. It has to refer to a key with map value.
;;
;; Define rules used for every site under the `:shared` key.
(def config-opts {:http-req-opts http-req-opts
                  :scraper-defs  {:shared       {:host    #(-> % :url urly/url-like urly/host-of)}
                                  "example.com" {:title   [:h3#title]
                                                 :content [:div.content]
                                                 :title_length (fn [doc] (count (doc :title)))}
                                  "usa.example.com" "example.com"}
                  :feeds ["http://example.com/rss.xml"]})

(def out-c (chan 2))

(with-fake-http
  ["http://example.com/rss.xml"
   "<?xml version=\"1.0\" encoding=\"utf-8\" ?>
    <rss version=\"2.0\" xml:base=\"http://example.com/rss.xml\">
      <channel>
        <item>
        <title>Foo</title>
        <link>http://example.com/foo.html</link>
        </item>
        <item>
        <title>Bar</title>
        <link>http://usa.example.com/bar.html</link>
        </item>
      </channel>
    </rss>"

   "http://example.com/foo.html"
   "<body>
      <h3 id='title'>Foo Title</h3>
      <div class='content'>Foo Content</div>
    </body>"

   "http://usa.example.com/bar.html"
   "<body>
      <h3 id='title'></h3>
      <div class='content'>Bar Content</div>
    </body>"]
  (def test-system
    (sys/build-scraper-system
      config-opts {:logger  (reify logger/ILogger (info [_ msg] (println msg))),
                   :storage {:doc-c out-c}}))
  (alter-var-root (var test-system) component/start)
  (def results (sort (<!! (async/into [] out-c))))
  (def result1 (first  results))
  (def result2 (second results))
  (alter-var-root (var test-system) component/stop))

;; Two results articles should be found
(expect 2 (count results))

;; ## Scraping attribute by a selector
;;
;; - When the retrieved web page has the selector defined the value from the web page is used.
;; - Otherwise for undefined or empty value for the selector the value already defined (likely to be from the seed) is kept.
(expect "Foo Title" (:title result1))
(expect "Bar" (:title result2))

;; ## Scraping attribute by a function
;; The attribute scraping function takes the _doc_ with the already scraped attributes and is supposed to return
;; the value for the new attribute.
;; The title in the seed was `"Foo"` overriden to `"Foo Title"`,
;; so the `:title_len` is expected to be taken from the new value
(expect 9 (:title_length result1))

;; ## Scraping shared attributes
;; Shared attributes are used in scraping every site.
(expect "example.com"     (:host result1))
(expect "usa.example.com" (:host result2))

;; ## URL attribute
;; URL is must have defined attribute for every `doc` for scraping, it's kept under `:url`.
(expect "http://example.com/foo.html"     (:url result1))
(expect "http://usa.example.com/bar.html" (:url result2))
