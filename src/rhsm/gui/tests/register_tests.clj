(ns rhsm.gui.tests.register_tests
  (:use [test-clj.testng :only [gen-class-testng
                                data-driven]]
        [rhsm.gui.tasks.test-config :only (config
                                           clientcmd)]
        [com.redhat.qe.verify :only (verify)]
        [slingshot.slingshot :only (try+
                                    throw+)]
        [clojure.string :only (blank?
                               split
                               trim)]
        rhsm.gui.tasks.tools
        gnome.ldtp)
  (:require [rhsm.gui.tasks.tasks :as tasks]
            [rhsm.gui.tasks.ui :as ui]
            [rhsm.gui.tests.base :as base]
            [clojure.tools.logging :as log]
            [rhsm.errors.utils :refer [normalize-exception-types]]
            [rhsm.gui.tasks.candlepin-tasks :as ctasks])
  (:import [org.testng.annotations
            Test
            BeforeClass
            BeforeGroups
            AfterGroups
            DataProvider]
           [rhsm.gui.tasks.tools Version]
           (org.testng SkipException)
           [com.github.redhatqe.polarize.metadata TestDefinition]
           [com.github.redhatqe.polarize.metadata DefTypes$Project]))

(def sys-log "/var/log/rhsm/rhsm.log")
(def gui-log "/var/log/ldtpd/ldtpd.log")
(def ns-log "rhsm.gui.tests.register_tests")

(defn get-userlists [username password]
  (let [owners (ctasks/get-owners username password)]
    (for [owner owners] (vector username password owner))))

(defn ^{BeforeClass {:groups ["setup"]}}
  setup [_]
  (tasks/screenshot-on-exception
   (try+
    (tasks/unregister)
    (catch [:type :not-registered] _)
    (catch Exception e
      (reset! (skip-groups :register) true)
      (throw e))))
  (when (or (bool (tasks/ui guiexist :register-dialog))
            (bool (tasks/ui guiexist :error-dialog)))
    (tasks/restart-app :force-kill? true)))

(defn ^{Test           {:groups       ["registration"
                                       "tier2"
                                       "tier1" "acceptance"
                                       "blockedByBug-995242"
                                       "blockedByBug-1251004"]
                        :dataProvider "userowners"}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-27627" "RHEL7-27020"]}}
  simple_register
  "Simple register with known username, password and owner."
  [_ user pass owner]
  (normalize-exception-types
   (try+
    (if owner
      (tasks/register user pass :owner owner)
      (tasks/register user pass))
    (catch [:type :already-registered]
        {:keys [unregister-first]} (unregister-first)))
   (verify (not (tasks/ui showing? :register-system)))
   (if owner
     (try
       (do
         (tasks/ui click :view-system-facts)
         (tasks/ui waittillwindowexist :facts-dialog 10)
         (let [facts-org (split (tasks/ui gettextvalue :facts-org) #" \(")
               read-owner (first facts-org)
               ownerid (trim
                        (:stdout
                         (run-command
                          "subscription-manager identity | grep 'org ID' | cut -d: -f 2")))
               read-ownerid (clojure.string/replace (last facts-org) #"\)$" "")]
           (verify (= owner read-owner))
           (verify (= ownerid read-ownerid))))
       (finally (if (bool (tasks/ui guiexist :facts-dialog))
                  (tasks/ui click :close-facts)))))))

(defn ^{Test           {:groups       ["registration"
                                       "tier2"
                                       "blockedByBug-1255805"
                                       "blockedByBug-1283749"
                                       "blockedByBug-718045"
                                       "blockedByBug-1194365"
                                       "blockedByBug-1249723"
                                       "blockedByBug-1194365"]
                        :dataProvider "bad-credentials and corresponding errors"}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36485" "RHEL7-61904"]}}
  register_bad_credentials
  "Checks error messages upon registering with bad credentials."
  [_ register-args expected-error]
  ;(skip-if-bz-open "1194365")
  (try+ (tasks/unregister) (catch [:type :not-registered] _))
  (when (or (bool (tasks/ui guiexist :register-dialog))
            (bool (tasks/ui guiexist :error-dialog)))
    (tasks/restart-app :force-kill? true))
  (let [test-fn (fn [args]
                  (try+ (apply tasks/register args)
                        (catch
                         [:type expected-error]
                         {:keys [type]}
                          type)))]
    (let [thrown-error (test-fn register-args)]
      (verify (and (= thrown-error expected-error) (action exists? :register-system)))
      (verify (bool (tasks/ui guiexist :register-dialog)))
      (tasks/ui click :register-close)
      (tasks/close-error-dialog))))

(defn ^{Test           {:groups ["registration"
                                 "tier2"
                                 "tier1" "acceptance"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-20120" "RHEL7-27021"]}}
  unregister
  "Simple unregister."
  [_]
  (normalize-exception-types
   (try+ (tasks/register (@config :username) (@config :password))
         (catch
             [:type :already-registered]
             {:keys [unregister-first]} (unregister-first)))
   (tasks/unregister)
   (verify (action exists? :register-system))))

(defn ^{Test           {:groups   ["registration"
                                   "tier3"
                                   "blockedByBug-918303"]
                        :priority (int 10)}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36992" "RHEL7-57124"]}}
  register_check_syslog
  "Asserts that register events are logged in the syslog."
  [_]
  (let [output (get-logging @clientcmd
                            sys-log
                            "register_check_syslog"
                            nil
                            (tasks/register-with-creds))]
    (verify (not (blank? output)))))

(defn ^{Test           {:groups           ["registration"
                                           "tier3"
                                           "blockedByBug-918303"]
                        :dependsOnMethods ["register_check_syslog"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-38181" "RHEL7-57125"]}}
  unregister_check_syslog
  "Asserts unregister events are logged in the syslog."
  [_]
  ;(tasks/register-with-creds)
  (let [output (get-logging @clientcmd
                            sys-log
                            "unregister_check_syslog"
                            nil
                            (tasks/unregister))]
    (verify (not (blank? output)))))

(defn ^{Test           {:groups ["registration"
                                 "tier2"
                                 "blockedByBug-822706"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36484" "RHEL7-55574"]}}
  check_auto_to_register_button
  "Checks that the register button converts to the auto-subscribe button after register."
  [_]
  (tasks/restart-app :unregister? true)
  (verify (and (tasks/ui showing? :register-system)
               (not (tasks/ui showing? :auto-attach))))
  (tasks/register-with-creds)
  (verify (and (tasks/ui showing? :auto-attach)
               (not (tasks/ui showing? :register-system)))))

(defn ^{Test           {:groups ["registration"
                                 "tier3"
                                 "blockedByBug-878609"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36989" "RHEL7-57121"]}}
  verify_password_tip
  "Checks to see if the passeword tip no longer contains red.ht"
  [_]
  (if (not (tasks/ui showing? :register-system))
    (tasks/unregister))
  (tasks/ui click :register-system)
  (tasks/ui waittillguiexist :register-dialog)
  (tasks/ui click :register)
  (try
    (let [tip (tasks/ui gettextvalue :password-tip)]
      (verify (not (substring? "red.ht" tip))))
    (finally
      (tasks/ui click :register-close))))

(defn ^{Test           {:groups ["registration"
                                 "tier3"
                                 "blockedByBug-920091"
                                 "blockedByBug-1039753"
                                 "blockedByBug-1037712"
                                 "blockedByBug-1034429"
                                 "blockedByBug-1170324"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36990" "RHEL7-57122"]}}
  check_traceback_unregister
  "Check there is no Tracebacks during unregister with GUI open"
  [_]
  (if (not (tasks/ui showing? :register-system))
    (tasks/unregister))
  (let [cmdout (atom nil)
        addput (fn [m] (swap! cmdout str " " (reduce str ((juxt :stdout :stderr) m))))
        logout (get-logging
                @clientcmd
                sys-log
                "Traceback-register-unregister-GUI-open"
                nil
                (addput (run-command
                         (str  "subscription-manager register --user="
                               (@config :username)
                               " --password="
                               (@config :password)
                               " --org="
                               (@config :owner-key)
                               " --auto-attach")))
                (addput (run-command "subscription-manager unregister")))]
    (verify (not (substring? "Traceback" logout)))
    (verify (not (substring? "Traceback" @cmdout)))))

(defn ^{Test           {:groups ["registration"
                                 "tier2"
                                 "blockedByBug-891621"
                                 "activation-register"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36483" "RHEL7-55573"]}}
  check_activation_key_register_dialog
  "checks whether checking activation key option followed by clicking default during
     register proceeds to register dialog and not to activation-key register dialog"
  [_]
  (try
    (if (not (tasks/ui showing? :register-system))
      (tasks/unregister))
    (tasks/ui click :register-system)
    (tasks/ui waittillguiexist :register-dialog)
    (tasks/ui settextvalue :register-server (ctasks/server-path))
    (tasks/ui check :activation-key-checkbox)
    (tasks/ui click :default-server)
    (tasks/ui click :register)
    (tasks/ui waittillguiexist :register-dialog)
    (verify (= "Please enter your Red Hat account information:"
               (tasks/ui gettextvalue :register-dialog "registration_header_label")))
    (finally
      (do
        (log/info "Finally Closing Dialog...")
        (if (bool (tasks/ui guiexist :register-dialog))
          (tasks/ui click :register-close))))))

(defn ^{AfterGroups {:groups ["registration"]
                     :value ["activation-register"]
                     :alwaysRun true}}
  after_check_activation_key_register_dialog [_]
  (tasks/set-conf-file-value "hostname" (@config :server-hostname))
  (tasks/set-conf-file-value "port" (@config :server-port))
  (tasks/set-conf-file-value "prefix" (@config :server-prefix))
  (tasks/restart-app :force-kill? true))

(defn ^{Test           {:groups ["registration"
                                 "tier2"
                                 "blockedByBug-1268102"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36486" "RHEL7-55575"]}}
  register_multi_click
  "Asserts that you can't click the register button multiple times
   and open multiple register dialogs"
  [_]
  (try+ (tasks/unregister) (catch [:type :not-registered] _))
  (verify (contains? (set (tasks/ui getallstates :register-system))  "enabled"))
  (tasks/ui click :register-system)
  (tasks/ui waittillguiexist :register-dialog)
  (verify (nil? (some #{"enabled"} (tasks/ui getallstates :register-system))))
  (tasks/ui click :register-system)
  (tasks/ui click :register-system)
  (case (get-release)
    "RHEL6"   (verify (apply distinct? (filter #(= "dlgSystemRegistration" %) (tasks/ui getwindowlist))))
    "RHEL7"   (verify (apply distinct? (filter #(= "dlgregister_dialog" %) (tasks/ui getwindowlist))))
    :no-verify)
  (tasks/ui click :register-close))

(defn ^{Test           {:groups ["registration"
                                 "tier3"
                                 "blockedByBug-1268094"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-36991" "RHEL7-57123"]}}
  unregister_traceback
  "Asserts that a traceback does not occur during unregister
   when pools are attached."
  [_]
  (try+ (tasks/unregister) (catch [:type :not-registered] _))
  (tasks/register-with-creds :re-register true)
  (let [avail (shuffle (ctasks/list-available))
        pools (take 5 (map :id avail))
        args (str " --pool" (clojure.string/join " --pool=" pools))]
    (run-command (str "subscription-manager attach" args))
    (tasks/restart-app)
    (verify (not (substring? "Traceback" (get-logging
                                          @clientcmd
                                          gui-log
                                          "Unregister-Traceback-Test"
                                          nil
                                          (tasks/unregister)))))))

(defn ^{Test           {:groups ["registration"
                                 "tier1" "acceptance"
                                 "blockedByBug-1330054"]}
        TestDefinition {:projectID  [`DefTypes$Project/RHEL6 `DefTypes$Project/RedHatEnterpriseLinux7]
                        :testCaseID ["RHEL6-27626" "RHEL7-49267"]}}
  check_default_subscription_url
  [_]
  (let [subman-ver (subman-version)
        rhsm-ver (map->Version {:major "1" :minor "16" :patch "6"})]
    (when (< (compare subman-ver rhsm-ver) 0)
      (throw (SkipException. "subscription-manager version is too old for this test")))
    (tasks/restart-app :unregister? true)
    (tasks/register-system)
    (tasks/ui click :default-server)
    (sleep 1000)
    (try
      (let [url (tasks/ui gettextvalue :register-server)]
        (verify (= url "subscription.rhsm.redhat.com:443/subscription")))
      (finally
        (tasks/ui click :register-close)))))

;;;;;;;;;;;;;;;;;;;;
;; DATA PROVIDERS ;;
;;;;;;;;;;;;;;;;;;;;

(defn ^{DataProvider {:name "userowners"}}
  get_userowners [_ & {:keys [debug]
                       :or {debug false}}]
  (log/info (str "======= Starting DataProvider: " ns-log "get_userowners()"))
  (if-not (assert-skip :register)
    (do
      (let [data (vec
                  (conj
                   (into
                    (if (and (@config :username1) (@config :password1))
                      (get-userlists (@config :username1) (@config :password1)))
                    (if (and (@config :username) (@config :password))
                      (get-userlists (@config :username) (@config :password))))
                                        ; https://bugzilla.redhat.com/show_bug.cgi?id=719378
                   (if (and (@config :username) (@config :password))
                     [(str (@config :username) "   ") (@config :password) nil])
                                        ; https://bugzilla.redhat.com/show_bug.cgi?id=719378
                   (if (and (@config :username) (@config :password))
                     [(str "   " (@config :username)) (@config :password) nil])))]
        (if-not debug
          (to-array-2d data)
          data)))
    (to-array-2d [])))

(defn ^{DataProvider {:name "bad-credentials and corresponding errors"}}
  get_bad_credentials_and_corresponding_errors [_]
  (if-not (assert-skip :register)
    (to-array-2d [[["sdf" "sdf"] :invalid-credentials]
                  [["test user" "password"] :invalid-credentials]
                  [["test user" ""] :no-password]
                  [["  " "  "] :no-username]
                  [["" ""] :no-username]
                  [["" "password"] :no-username]
                  [["sdf" ""] :no-password]
                  [[(@config :username) (@config :password) :system-name-input ""] :no-system-name]])
    (to-array-2d [])))

(gen-class-testng)
