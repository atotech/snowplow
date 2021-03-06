/*
 * Copyright (c) 2014-2016 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow
package collectors
package scalastream

// Scala
import scala.collection.mutable.MutableList

// Akka
import akka.actor.{ActorSystem, Props}

// Specs2 and Spray testing
import org.specs2.matcher.AnyMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.{Scope,Fragments}
import spray.testkit.Specs2RouteTest

// Spray
import spray.http.{DateTime, HttpHeader, HttpRequest, HttpCookie, RemoteAddress}
import spray.http.HttpHeaders.{
  Cookie,
  `Set-Cookie`,
  `Remote-Address`,
  `Raw-Request-URI`
}

// Config
import com.typesafe.config.{ConfigFactory,Config,ConfigException}

// Thrift
import org.apache.thrift.TDeserializer

// Snowplow
import sinks._
import CollectorPayload.thrift.model1.CollectorPayload

class PostSpec extends Specification with Specs2RouteTest with
     AnyMatchers {
   val testConf: Config = ConfigFactory.parseString("""
collector {
  interface = "0.0.0.0"
  port = 8080

  production = true

  p3p {
    policyref = "/w3c/p3p.xml"
    CP = "NOI DSP COR NID PSA OUR IND COM NAV STA"
  }

  cookie {
    enabled = true
    expiration = 365 days
    name = sp
    domain = "test-domain.com"
    Kinesis-Part-Key = "666"
  }

  sink {
    enabled = "test"

    kinesis {
      aws {
        access-key: "cpf"
        secret-key: "cpf"
      }
      stream {
        region: "us-east-1"
        good: "snowplow_collector_example"
        bad: "snowplow_collector_example"
      }
      backoffPolicy {
        minBackoff: 3000 # 3 seconds
        maxBackoff: 600000 # 5 minutes
      }
    }

    kafka {
      brokers: "localhost:9092"

      topic {
        good: "good-topic"
        bad: "bad-topic"
      }
    }

    buffer {
      byte-limit: 4000000 # 4MB
      record-limit: 500 # 500 records
      time-limit: 60000 # 1 minute
    }
  }
}
""")
  val collectorConfig = new CollectorConfig(testConf)
  val sink = new TestSink
  val sinks = CollectorSinks(sink, sink)
  val responseHandler = new ResponseHandler(collectorConfig, sinks)
  val collectorService = new CollectorService(collectorConfig, responseHandler, system)
  val thriftDeserializer = new TDeserializer

  // By default, spray will always add Remote-Address to every request
  // when running with the `spray.can.server.remote-address-header`
  // option. However, the testing does not read this option and a
  // remote address always needs to be set.
  def CollectorPost(uri: String, cookie: Option[`HttpCookie`] = None,
      remoteAddr: String = "127.0.0.1") = {
    val headers: MutableList[HttpHeader] =
      MutableList(`Remote-Address`(remoteAddr),`Raw-Request-URI`(uri))
    cookie.foreach(headers += `Cookie`(_))
    Post(uri).withHeaders(headers.toList)
  }

  "Snowplow's Scala collector" should {
    "return a cookie expiring at the correct time" in {
      CollectorPost("/com.snowplowanalytics.snowplow/tp2") ~> collectorService.collectorRoute ~> check {
        headers must not be empty

        val httpCookies: List[HttpCookie] = headers.collect {
          case `Set-Cookie`(hc) => hc
        }
        httpCookies must not be empty

        // Assume we only return a single cookie.
        // If the collector is modified to return multiple cookies,
        // this will need to be changed.
        val httpCookie = httpCookies(0)

        httpCookie.name must beEqualTo(collectorConfig.cookieName.get)
        httpCookie.name must beEqualTo("sp")
        httpCookie.path must beSome("/")
        httpCookie.domain must beSome
        httpCookie.domain.get must be(collectorConfig.cookieDomain.get)
        httpCookie.expires must beSome
        httpCookie.content.matches("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        val expiration = httpCookie.expires.get
        val offset = expiration.clicks - collectorConfig.cookieExpiration.get - DateTime.now.clicks
        offset.asInstanceOf[Int] must beCloseTo(0, 2000) // 1000 ms window.
      }
    }
    "return a cookie containing nuid query parameter" in {
      CollectorPost("/com.snowplowanalytics.snowplow/tp2?nuid=UUID_Test_New") ~> collectorService.collectorRoute ~> check {
        headers must not be empty

        val httpCookies: List[HttpCookie] = headers.collect {
          case `Set-Cookie`(hc) => hc
        }
        httpCookies must not be empty

        // Assume we only return a single cookie.
        // If the collector is modified to return multiple cookies,
        // this will need to be changed.
        val httpCookie = httpCookies(0)

        httpCookie.name must beEqualTo(collectorConfig.cookieName.get)
        httpCookie.name must beEqualTo("sp")
        httpCookie.path must beSome("/")
        httpCookie.domain must beSome
        httpCookie.domain.get must be(collectorConfig.cookieDomain.get)
        httpCookie.expires must beSome
        httpCookie.content must beEqualTo("UUID_Test_New")
        val expiration = httpCookie.expires.get
        val offset = expiration.clicks - collectorConfig.cookieExpiration.get - DateTime.now.clicks
        offset.asInstanceOf[Int] must beCloseTo(0, 3600000) // 1 hour window.
      }
    }
    "return the same cookie as passed in" in {
      CollectorPost("/com.snowplowanalytics.snowplow/tp2", Some(HttpCookie(collectorConfig.cookieName.get, "UUID_Test"))) ~>
          collectorService.collectorRoute ~> check {
        val httpCookies: List[HttpCookie] = headers.collect {
          case `Set-Cookie`(hc) => hc
        }
        // Assume we only return a single cookie.
        // If the collector is modified to return multiple cookies,
        // this will need to be changed.
        val httpCookie = httpCookies(0)

        httpCookie.content must beEqualTo("UUID_Test")
      }
    }
    "override cookie with nuid parameter" in {
      CollectorPost("/com.snowplowanalytics.snowplow/tp2?nuid=UUID_Test_New", Some(HttpCookie("sp", "UUID_Test"))) ~>
          collectorService.collectorRoute ~> check {
        val httpCookies: List[HttpCookie] = headers.collect {
          case `Set-Cookie`(hc) => hc
        }
        // Assume we only return a single cookie.
        // If the collector is modified to return multiple cookies,
        // this will need to be changed.
        val httpCookie = httpCookies(0)

        httpCookie.content must beEqualTo("UUID_Test_New")
      }
    }
    "return a P3P header" in {
      CollectorPost("/com.snowplowanalytics.snowplow/tp2") ~> collectorService.collectorRoute ~> check {
        val p3pHeaders = headers.filter {
          h => h.name.equals("P3P")
        }
        p3pHeaders.size must beEqualTo(1)
        val p3pHeader = p3pHeaders(0)

        val policyRef = collectorConfig.p3pPolicyRef
        val CP = collectorConfig.p3pCP
        p3pHeader.value must beEqualTo(
          "policyref=\"%s\", CP=\"%s\"".format(policyRef, CP))
      }
    }
    "store the expected event as a serialized Thrift object in the enabled sink" in {
      val payloadData = "param1=val1&param2=val2"
      val storedRecordBytes = responseHandler.cookie(payloadData, null, None,
        None, "localhost", RemoteAddress("127.0.0.1"), new HttpRequest(), None, "/com.snowplowanalytics.snowplow/tp2", false, null)._2

      val storedEvent = new CollectorPayload
      this.synchronized {
        thriftDeserializer.deserialize(storedEvent, storedRecordBytes.head)
      }

      storedEvent.timestamp must beCloseTo(DateTime.now.clicks, 60000)
      storedEvent.encoding must beEqualTo("UTF-8")
      storedEvent.ipAddress must beEqualTo("127.0.0.1")
      storedEvent.collector must beEqualTo("ssc-0.9.0-test")
      storedEvent.path must beEqualTo("/com.snowplowanalytics.snowplow/tp2")
      storedEvent.querystring must beEqualTo(payloadData)
    }
  }
}
