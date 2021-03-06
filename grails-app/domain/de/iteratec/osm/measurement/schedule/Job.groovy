/* 
* OpenSpeedMonitor (OSM)
* Copyright 2014 iteratec GmbH
* 
* Licensed under the Apache License, Version 2.0 (the "License"); 
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
* 	http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software 
* distributed under the License is distributed on an "AS IS" BASIS, 
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
* See the License for the specific language governing permissions and 
* limitations under the License.
*/

package de.iteratec.osm.measurement.schedule

import grails.util.Environment

import org.grails.databinding.BindUsing
import org.grails.taggable.Taggable
import org.quartz.CronExpression

import de.iteratec.isj.quartzjobs.*
import de.iteratec.osm.measurement.script.Script
import de.iteratec.osm.measurement.environment.Location

/**
 * <p>
 * A web page test job definition.
 * </p>
 * 
 * @see Script
 */
class Job implements Taggable {
	def jobProcessingService
	
	Long id;
	
	String label;
	Script script;
	Location location;
	Date lastRun;
	
	@BindUsing({ obj, source -> source['description'] })
	String description;
	int runs = 1;
	
	boolean active;
	boolean firstViewOnly;
	boolean captureVideo;
	/**
	 * Stop Test at Document Complete.
	 */
	boolean web10 
	/**
	 * Disable Javascript
	 */
	boolean noscript
	/** 
	 * Clear SSL Certificate Caches
	 */
	boolean clearcerts
	/**
	 * Ignore SSL Certificate Errors
	 * e.g. Name mismatch, Self-signed certificates, etc.
	 */
	boolean	ignoreSSL
	/**
	 * Disable Compatibility View (IE Only)
	 * Forces all pages to load in standards mode
	 */
	boolean standards
	/**
	 *  Capture network packet trace (tcpdump)
	 */
	boolean tcpdump
	/**
	 * Save response bodies (For text resources)
	 */
	boolean bodies
	/**
	 * Continuous Video Capture
	 * Unstable/experimental, may cause tests to fail
	 */
	boolean continuousVideo
	/**
	 * Preserve original User Agent string
	 * Do not add PTST to the browser UA string
	 */
	boolean keepua
	
	/**
	 * Pre-configured {@link ConnectivityProfile} associated with this Job.
	 */
	ConnectivityProfile connectivityProfile
	
	/**
	 * Whether or not custom values for bandwidthDown, bandwidthUp, latency and packetLoss are set for this job.
	 * If true, {@link #bandwidthDown}, {@link #bandwidthUp}, {@link #latency} and {@link #packetLoss} should be set.
	 * If false an  {@link #connectivityProfile} should exist for this job.
	 */
	boolean customConnectivityProfile 
	
	/**
	 * Bandwidth to set for downlink.
	 * @see https://sites.google.com/a/webpagetest.org/docs/advanced-features/webpagetest-restful-apis
	 */
	Integer bandwidthDown
	/**
	 * Bandwidth to set for uplink.
	 * @see https://sites.google.com/a/webpagetest.org/docs/advanced-features/webpagetest-restful-apis
	 */
	Integer bandwidthUp
	/**
	 * TCP-Latency to set for the measurement.
	 * @see https://sites.google.com/a/webpagetest.org/docs/advanced-features/webpagetest-restful-apis
	 */
	Integer latency
	/**
	 * Packet loss rate to set for the measurement.
	 * @see https://sites.google.com/a/webpagetest.org/docs/advanced-features/webpagetest-restful-apis
	 */
	Integer packetLoss
	
	
	/**
	 * @deprecated Use executionSchedule instead
	 */
	@Deprecated 
	Integer frequencyInMin;
	Integer maxDownloadTimeInMinutes 
		
	/**
	 * Set to label of this job's script if script
	 * contains no setEventName statements.
	 */
	String eventNameIfUnknown;
	
	Map<String, String> variables
	
	/**
	 * A cron string 
	 */
	String executionSchedule;
	
	boolean provideAuthenticateInformation
	String authUsername
	String authPassword
	
	/**
	 * The {@link JobGroup} this job is assigned to <em>or</em> 
	 */
	JobGroup jobGroup;	
	static belongsTo=[jobGroup: JobGroup];
	
	static transients = ['nextExecutionTime']
	
	static constraints = {
		label(maxSize: 255, blank: false, unique: true)
		script()
		location()
		lastRun(nullable: true)
		jobGroup()
		
		description(widget: 'textarea')
		runs(range: 1..25)
	
		firstViewOnly(nullable: true)
		captureVideo(nullable: true)
		
		web10(nullable: true)
		noscript(nullable: true)
		clearcerts(nullable: true)
		ignoreSSL(nullable: true)
		standards(nullable: true)
		tcpdump(nullable: true)
		bodies(nullable: true)
		continuousVideo(nullable: true)
		keepua(nullable: true)

		// If custom is set, no ConnectivityProfile may be specified and
		// bandwidthDown, bandwidthUp, latency and packetLoss may not  be null.	
		connectivityProfile(nullable: true, validator: { profile, instance ->
			return !instance.customConnectivityProfile || (instance.customConnectivityProfile && !profile && instance.bandwidthDown != null && instance.bandwidthUp != null && instance.latency != null && instance.packetLoss != null);
		})
		bandwidthDown(nullable: true)
		bandwidthUp(nullable: true)
		latency(nullable: true)
		packetLoss(nullable: true)		
		frequencyInMin(nullable: true)
		maxDownloadTimeInMinutes(range: 10..240)
		eventNameIfUnknown(nullable: true)
		variables(nullable: true)
		
		
		// if an executionSchedule is set, make sure that it is a valid Cron expression
		executionSchedule(nullable: true, validator: {
			if (it != null && !CronExpression.isValidExpression(it)) {
				return ['executionScheduleInvalid']
			}
		})
		// Job may only be active if it has an executionSchedule 
		active(nullable: false, validator: { active, obj -> 
			if (active && !obj.executionSchedule) {
				return ['executionScheduleMissing']
			}
		})
		
		provideAuthenticateInformation()
		authUsername(nullable: true, maxSize: 255)
		authPassword(nullable: true, maxSize: 255, password: true)
	}
	
	static mapping = {
		sort 'label':'asc'
		customConnectivityProfile defaultValue: false
	}
	
	def beforeValidate() {
		description = description?.trim()
	}
	
	String toString(){
		label
	}
			
	Date getNextExecutionTime() { 
		return executionSchedule ? CronExpressionFormatter.getNextValidTimeAfter(new CronExpression(executionSchedule), new Date()) : null
	}
	
	private void performQuartzScheduling(boolean start) {
		if (Environment.getCurrent() != Environment.TEST) {
			if (start) {
				jobProcessingService.scheduleJob(this)
			} else {
				jobProcessingService.unscheduleJob(this)
			}
		}
	}
	
	def afterInsert() {
		performQuartzScheduling(active)
	}

	def afterUpdate() {
		performQuartzScheduling(active)
	}
	
	def beforeDelete() {
		performQuartzScheduling(false)
	}
}
