import java.nio.charset.Charset

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.stages.{Utf8Decode, Utf8Encode}

final class Utf8CodecTest extends TestBase {

  "Utf8Encode" should "work" in
    withMaterializer { mat =>
      futureOk {
        val strings = (1 to 100).map(_.toString).toList
        val byteStrings = strings.map(ByteString(_, Charset.forName("UTF-8")))
        val futureByteString =
          Source(strings)
            .via(Utf8Encode)
            .runReduce(_ ++ _)(mat)
        futureByteString.map(_ should be (byteStrings.reduce(_ ++ _)))(mat.executionContext)
      }
    }

  "Utf8Decode" should "work #1" in
    withMaterializer { mat =>
      futureOk {
        val strings = (1 to 100).map(_.toString).toList
        val byteStrings = strings.map(ByteString(_, Charset.forName("UTF-8")))

        val futureString =
          Source(byteStrings)
            .via(Utf8Decode)
            .runReduce(_ + _)(mat)

        futureString.map(_ should be(strings.reduce(_ + _)))(mat.executionContext)
      }
    }

  it should "work #2" in
    futureOk(withMaterializer { mat =>
      val strings = (1 to 100).map(_.toString).toList
      val byteString =
        strings
          .map(ByteString(_, Charset.forName("UTF-8")))
          .reduce(_ ++ _)

      val futureString =
        Source(List(byteString))
          .via(Utf8Decode)
          .runReduce(_ + _)(mat)

      futureString.map(_ should be (strings.reduce(_ + _)))(mat.executionContext)
    })

  it should "work #3" in
    withMaterializer { mat =>
      futureOk {
        val strings = (1 to 100).map(_.toString).toList
        val byteString =
          strings
            .map(ByteString(_, Charset.forName("UTF-8")))
            .reduce(_ ++ _)

        val futureString =
          Source(for (byte <- byteString) yield ByteString(Array(byte)))
            .via(Utf8Decode)
            .runReduce(_ + _)(mat)

        futureString.map(_ should be (strings.reduce(_ + _)))(mat.executionContext)
      }
    }
}
