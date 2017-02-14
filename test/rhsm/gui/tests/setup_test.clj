(ns rhsm.gui.tests.setup-test
  (:require  [clojure.test :refer :all])
  (:import [org.testng TestNG]))

;; this test is run this way:
;;   lein test rhsm.gui.tests.setup-test
;; or
;;   lein test :only rhsm.gui.tests.setup-test/tier1-all-suites-test
;; this test is to run the whole testng machinery with our tests
(deftest tier1-all-suites-test
  (TestNG/main (into-array String ["suites/sm-cli-tier1-testng-suite.xml" "suites/sm-gui-tier1-testng-suite.xml"])))

(deftest tier1-all-cli-suites-test
  (TestNG/main (into-array String ["suites/sm-cli-tier1-testng-suite.xml"])))
