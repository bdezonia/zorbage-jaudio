/*
 * zorbage-jaudio: code for using the java sound api to open audio files into zorbage data structures for further processing
 *
 * Copyright (C) 2023 Barry DeZonia
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package nom.bdezonia.zorbage.jaudio;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import nom.bdezonia.zorbage.algebra.G;
import nom.bdezonia.zorbage.coordinates.CoordinateSpace;
import nom.bdezonia.zorbage.coordinates.LinearNdCoordinateSpace;
import nom.bdezonia.zorbage.data.DimensionedDataSource;
import nom.bdezonia.zorbage.data.DimensionedStorage;
import nom.bdezonia.zorbage.misc.DataBundle;
import nom.bdezonia.zorbage.sampling.IntegerIndex;
import nom.bdezonia.zorbage.type.integer.int128.SignedInt128Member;
import nom.bdezonia.zorbage.type.integer.int128.UnsignedInt128Member;
import nom.bdezonia.zorbage.type.integer.int16.SignedInt16Member;
import nom.bdezonia.zorbage.type.integer.int16.UnsignedInt16Member;
import nom.bdezonia.zorbage.type.integer.int32.SignedInt32Member;
import nom.bdezonia.zorbage.type.integer.int32.UnsignedInt32Member;
import nom.bdezonia.zorbage.type.integer.int64.SignedInt64Member;
import nom.bdezonia.zorbage.type.integer.int64.UnsignedInt64Member;
import nom.bdezonia.zorbage.type.integer.int8.SignedInt8Member;
import nom.bdezonia.zorbage.type.integer.int8.UnsignedInt8Member;
import nom.bdezonia.zorbage.type.real.float32.Float32Member;
import nom.bdezonia.zorbage.type.real.float64.Float64Member;

// TODO
// - should I keep each channel as a separate data set in the bundle?
// - make sure my A Law and Mu Law y percentage calcs are correct
//     abs(number.doubleValue() / MAX.doubleValue()).
//     Maybe the code is wrong how it is. Maybe it is a twos complement
//     approach I am not taking. I am treating number as a fraction of
//     MAX e.g. number as an unsigned number with a sign bit.
//     Also besides a y percentage maybe we mult by y original range?

// The java.sound spec supports WAV, AU, and AIFF file formats
//   And various encodings of these formats
//   And also MIDI and RMF (Rich Media Format) files (which I'm not handling yet)

public class JAudio {

	// do not instantiate
	
	private JAudio() { }

	public static void main(String[] args) {
		
		if (args.length != 1) {
			
			System.out.println("must pass one WAV/AU/AIFF file name as a command line argument");
			
			System.exit(1);
		}

		DataBundle bundle = readAllDatasets(args[0]);
		
		if (bundle.bundle().size() == 0) {
			
			System.out.println("COULD NOT READ " + args[0]);
			
			System.exit(2);
		}

		System.out.println("READ " + args[0]);
		
		System.exit(0);
	}

	/**
	 * 
	 * @param filename
	 * @return
	 */
	public static
	
		DataBundle
		
			readAllDatasets(String filename)
	{
		try {
		
			URI uri = new URI("file", null, new File(filename).getAbsolutePath(), null);
			
			return readAllDatasets(uri);
	
		} catch (URISyntaxException e) {
			
			throw new IllegalArgumentException("Bad name for file: "+e.getMessage());
		}
	}

	/**
	 * 
	 * @param fileURI
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static DataBundle readAllDatasets(URI fileURI) {

		DataBundle bundle = new DataBundle();
		
		try {
			
			InputStream stream = fileURI.toURL().openStream();

			AudioInputStream ais = AudioSystem.getAudioInputStream(stream);
			
			BufferedInputStream bis = new BufferedInputStream(ais);

			// determine dimensions
			
			long numFrames = ais.getFrameLength();

			AudioFormat af = ais.getFormat();
			
			int numChannels = af.getChannels();

			long[] dims = new long[] {numFrames, numChannels};
			
			// determine pixel type
			
			int bitsPerSample = af.getSampleSizeInBits();
			
			Encoding encoding = af.getEncoding();

			Object type = findType(encoding, bitsPerSample);
			
			DimensionedDataSource<?> data =
					
					allocateData(fileURI, type, dims, 1.0/af.getFrameRate());
			
			boolean bigEndian = af.isBigEndian();
			
			IntegerIndex idx = new IntegerIndex(2);
			
			int bytesPerSample = bitsPerSample / 8 + (bitsPerSample % 8 == 0 ? 0 : 1);
			
			int frameSize = af.getFrameSize();
			
			if (frameSize == AudioSystem.NOT_SPECIFIED) {
				
				frameSize = numChannels * bytesPerSample;
			}
			
			System.out.println("Frame size       = "+frameSize);
			System.out.println("Num frames       = "+numFrames);
			System.out.println("Num channels     = "+numChannels);
			System.out.println("bits per sample  = "+bitsPerSample);
			System.out.println("bytes per sample = "+bytesPerSample);
			System.out.println("ENCODING         = "+encoding);
			System.out.println("dims             = "+Arrays.toString(dims));

			// iterate the file and set all the channels at each time point
			//   to the pixels read at that time point in the file

			byte[] bytes = new byte[bytesPerSample];
			byte[] reversedBytes = new byte[bytesPerSample];
			
			for (long f = 0; f < numFrames; f++) {

				idx.set(0, f);
				
				for (long c = 0; c < numChannels; c++) {
					
					idx.set(1, c);
					
					// determine how to read a value

					bis.read(bytes);
					
					if (!bigEndian) {
						
						for (int b = 0; b < bytes.length; b++) {
							reversedBytes[bytes.length-1-b] = bytes[b];
						}

						for (int b = 0; b < bytes.length; b++) {
							bytes[b] = reversedBytes[b];
						}
					}

					// use metadata to correctly write the data to our structures
					
					setSample(data, idx, bytes, encoding, bitsPerSample);
				}
			}

			// store some metadata
			
			data.metadata().putLong("number of channels", numChannels);
			
			data.metadata().putLong("number of frames", numFrames);
			
			data.metadata().putLong("frameSize", frameSize);
			
			data.metadata().putInt("bits per sample", bitsPerSample);

			data.metadata().putInt("bytes per sample", bytesPerSample);

			data.metadata().putString("encoding", encoding.toString());

			// now store the read data into the DataBundle
			
			if (type instanceof Float32Member) {
				
				bundle.flts.add( (DimensionedDataSource<Float32Member>) data );
			}
			
			if (type instanceof Float64Member) {
				
				bundle.dbls.add( (DimensionedDataSource<Float64Member>) data );
			}
			
			if (type instanceof SignedInt8Member) {
				
				bundle.int8s.add( (DimensionedDataSource<SignedInt8Member>) data );
			}
			
			if (type instanceof SignedInt16Member) {
				
				bundle.int16s.add( (DimensionedDataSource<SignedInt16Member>) data );
			}
			
			if (type instanceof SignedInt32Member) {
				
				bundle.int32s.add( (DimensionedDataSource<SignedInt32Member>) data );
			}
			
			if (type instanceof SignedInt64Member) {
				
				bundle.int64s.add( (DimensionedDataSource<SignedInt64Member>) data );
			}
			
			if (type instanceof SignedInt128Member) {
				
				bundle.int128s.add( (DimensionedDataSource<SignedInt128Member>) data );
			}
			
			if (type instanceof UnsignedInt8Member) {
				
				bundle.uint8s.add( (DimensionedDataSource<UnsignedInt8Member>) data );
			}
			
			if (type instanceof UnsignedInt16Member) {
				
				bundle.uint16s.add( (DimensionedDataSource<UnsignedInt16Member>) data );
			}
			
			if (type instanceof UnsignedInt32Member) {
				
				bundle.uint32s.add( (DimensionedDataSource<UnsignedInt32Member>) data );
			}
			
			if (type instanceof UnsignedInt64Member) {
				
				bundle.uint64s.add( (DimensionedDataSource<UnsignedInt64Member>) data );
			}
			
			if (type instanceof UnsignedInt128Member) {
				
				bundle.uint128s.add( (DimensionedDataSource<UnsignedInt128Member>) data );
			}
			
			// else what the hell is it? maybe throw an exception?
		
		} catch (IOException e) {
		
			throw new IllegalArgumentException(e.getMessage());

		} catch (UnsupportedAudioFileException e) {
			
			throw new IllegalArgumentException(e.getMessage());
		}

		return bundle;
	}

	private static
	
		Object
		
			findType(AudioFormat.Encoding encoding, int bitsPerSample)
	{
		if (encoding == Encoding.ALAW) {
			
			// https://en.wikipedia.org/wiki/A-law_algorithm
			
			// resulting data is float/double
			
			return new Float64Member();
		}
		else if (encoding == Encoding.PCM_FLOAT) {
			
			// resulting data is float/double
			
			// I assume 32 or 64 bit. maybe my 16 bit would work if needed.
			
			if (bitsPerSample >= 1 && bitsPerSample <= 32)
				
				return new Float32Member();
			
			else if (bitsPerSample <= 64)
				
				return new Float64Member();
				
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.PCM_SIGNED) {

			// signed 8 or 16 or 24 or 32 up to 128 bit
			
			if (bitsPerSample >= 1 && bitsPerSample <= 8)
				
				return new SignedInt8Member();
			
			else if (bitsPerSample <= 16)
				
				return new SignedInt16Member();
			
			else if (bitsPerSample <= 32)
				
				return new SignedInt32Member();
			
			else if (bitsPerSample <= 64)
				
				return new SignedInt64Member();
			
			else if (bitsPerSample <= 128)
				
				return new SignedInt128Member();
			
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.PCM_UNSIGNED) {

			// unsigned 8 or 16 or 24 or 32 up to 128 bit
			
			if (bitsPerSample >= 1 && bitsPerSample <= 8)
				
				return new UnsignedInt8Member();
			
			else if (bitsPerSample <= 16)
				
				return new UnsignedInt16Member();
			
			else if (bitsPerSample <= 32)
				
				return new UnsignedInt32Member();
			
			else if (bitsPerSample <= 64)
				
				return new UnsignedInt64Member();
			
			else if (bitsPerSample <= 128)
				
				return new UnsignedInt128Member();
			
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.ULAW) {
			
			// https://en.wikipedia.org/wiki/%CE%9C-law_algorithm
			
			// resulting data is float/double
			
			return new Float64Member();
		}
		else {
			
			System.out.print("Unknown encoding in audio file: "+encoding);
			
			System.exit(1);
		}
		
		return null;
	}

	private static
	
		DimensionedDataSource<?>
	
			allocateData(URI fileURI, Object type, long[] dims, double secondsPerFrame)
	{
		// make multidim dataset with time and channels
		
		DimensionedDataSource<?> data = null;
		
		if (type instanceof SignedInt8Member)
			data = DimensionedStorage.allocate(G.INT8.construct(), dims);
		
		if (type instanceof SignedInt16Member)
			data = DimensionedStorage.allocate(G.INT16.construct(), dims);
		
		if (type instanceof SignedInt32Member)
			data = DimensionedStorage.allocate(G.INT32.construct(), dims);
		
		if (type instanceof SignedInt64Member)
			data = DimensionedStorage.allocate(G.INT64.construct(), dims);
		
		if (type instanceof SignedInt128Member)
			data = DimensionedStorage.allocate(G.INT128.construct(), dims);
		
		if (type instanceof UnsignedInt8Member)
			data = DimensionedStorage.allocate(G.UINT8.construct(), dims);
		
		if (type instanceof UnsignedInt16Member)
			data = DimensionedStorage.allocate(G.UINT16.construct(), dims);
		
		if (type instanceof UnsignedInt32Member)
			data = DimensionedStorage.allocate(G.UINT32.construct(), dims);
		
		if (type instanceof UnsignedInt64Member)
			data = DimensionedStorage.allocate(G.UINT64.construct(), dims);
		
		if (type instanceof UnsignedInt128Member)
			data = DimensionedStorage.allocate(G.UINT128.construct(), dims);
		
		if (type instanceof Float32Member)
			data = DimensionedStorage.allocate(G.FLT.construct(), dims);
		
		if (type instanceof Float64Member)
			data = DimensionedStorage.allocate(G.DBL.construct(), dims);
		
		if (data == null) {
			return null;
		}

		data.setSource(fileURI.toString());
		
		data.setValueType("audio");
		data.setValueUnit("amplitude");
		
		data.setAxisType(0, "time");
		data.setAxisUnit(0, "seconds");

		data.setAxisType(1, "channel");
		data.setAxisUnit(1, "number");
		
		// set the scale of the time axis
		
		CoordinateSpace cs = new LinearNdCoordinateSpace(
									new BigDecimal[] {BigDecimal.valueOf(secondsPerFrame), BigDecimal.ONE},
									new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});

		data.setCoordinateSpace(cs);
		
		return data;
	}

	@SuppressWarnings("unchecked")
	private static
	
		void
		
			setSample(
					DimensionedDataSource<?> data,
					IntegerIndex idx,
					byte[] sampleBytes,
					AudioFormat.Encoding encoding,
					int bitsPerSample)
	{
		BigInteger number = BigInteger.valueOf(sampleBytes[0] & 0xff);
		
		for (int i = 1; i < sampleBytes.length; i++) {
			
			number = number.shiftLeft(8).add( BigInteger.valueOf(sampleBytes[i] & 0xff) );
		}
		
		if (encoding == Encoding.ALAW) {
			
			// https://en.wikipedia.org/wiki/A-law_algorithm
			
			// resulting data is float/double

			boolean negative = sampleBytes[0] < 0;
			
			number = BigInteger.valueOf(sampleBytes[0] & 0x7f);
			
			for (int i = 1; i < sampleBytes.length; i++) {
				
				number = number.shiftLeft(8).add( BigInteger.valueOf(sampleBytes[i] & 0xff) );
			}
			
			BigInteger MAX = BigInteger.valueOf(127);
			
			for (int i = 1; i < sampleBytes.length; i++) {
				
				MAX = MAX.shiftLeft(8).add( BigInteger.valueOf(255) );
			}

			double y = number.doubleValue() / MAX.doubleValue();
			
			Float64Member value = G.DBL.construct();
			
			double A = 87.6;
			
			double magic_constant = 1.0 + Math.log(A);
			
			double cutoff = 1.0 / magic_constant;

			double calculation;
			
			if (y < cutoff) {
				
				calculation = y * (magic_constant / A);
			}
			else {

				calculation = Math.pow(Math.E, y * magic_constant - 1.0);
			}
			
			if (negative) calculation = -calculation;
			
			value.setV(calculation);
			
			((DimensionedDataSource<Float64Member>) data).set(idx, value);
		}
		else if (encoding == Encoding.PCM_FLOAT) {
			
			// resulting data is float/double
			
			// I assume 32 or 64 bit. maybe my 16 bit would work if needed.
			
			if (bitsPerSample >= 1 && bitsPerSample <= 32) {
				
				Float32Member value = G.FLT.construct();

				value.setV(Float.intBitsToFloat(number.intValue()));
				
				((DimensionedDataSource<Float32Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 64) {
				
				Float64Member value = G.DBL.construct();
				
				value.setV(Double.longBitsToDouble(number.longValue()));
				
				((DimensionedDataSource<Float64Member>) data).set(idx, value);
			}
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.PCM_SIGNED) {

			// signed 8 or 16 or 24 or 32 up to 64 bit
			
			if (bitsPerSample >= 1 && bitsPerSample <= 8) {
				
				SignedInt8Member value = G.INT8.construct();
				
				value.setV(number.byteValue());
				
				((DimensionedDataSource<SignedInt8Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 16) {
				
				SignedInt16Member value = G.INT16.construct();
				
				value.setV(number.shortValue());
				
				((DimensionedDataSource<SignedInt16Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 32) {
				
				SignedInt32Member value = G.INT32.construct();
				
				value.setV(number.intValue());
				
				((DimensionedDataSource<SignedInt32Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 64) {
				
				SignedInt64Member value = G.INT64.construct();
				
				value.setV(number.longValue());
				
				((DimensionedDataSource<SignedInt64Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 128) {
				
				SignedInt128Member value = G.INT128.construct();
				
				value.setV(number);
				
				((DimensionedDataSource<SignedInt128Member>) data).set(idx, value);
			}
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.PCM_UNSIGNED) {

			// unsigned 8 or 16 or 24 or 32 up to 128 bit
			
			if (bitsPerSample >= 1 && bitsPerSample <= 8) {
				
				UnsignedInt8Member value = G.UINT8.construct();
				
				value.setV(number.shortValue());
				
				((DimensionedDataSource<UnsignedInt8Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 16) {
				
				UnsignedInt16Member value = G.UINT16.construct();
				
				value.setV(number.intValue());
				
				((DimensionedDataSource<UnsignedInt16Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 32) {
				
				UnsignedInt32Member value = G.UINT32.construct();
				
				value.setV(number.longValue());
				
				((DimensionedDataSource<UnsignedInt32Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 64) {
				
				UnsignedInt64Member value = G.UINT64.construct();
				
				value.setV(number);
				
				((DimensionedDataSource<UnsignedInt64Member>) data).set(idx, value);
			}
			else if (bitsPerSample <= 128) {
				
				UnsignedInt128Member value = G.UINT128.construct();
				
				value.setV(number);
				
				((DimensionedDataSource<UnsignedInt128Member>) data).set(idx, value);
			}
			else
				throw new IllegalArgumentException("bits per sample is weird! "+bitsPerSample);
		}
		else if (encoding == Encoding.ULAW) {
			
			// https://en.wikipedia.org/wiki/%CE%9C-law_algorithm

			// resulting data is float/double

			boolean negative = sampleBytes[0] < 0;
			
			number = BigInteger.valueOf(sampleBytes[0] & 0x7f);
			
			for (int i = 1; i < sampleBytes.length; i++) {
				
				number = number.shiftLeft(8).add( BigInteger.valueOf(sampleBytes[i] & 0xff) );
			}
			
			BigInteger MAX = BigInteger.valueOf(127);
			
			for (int i = 1; i < sampleBytes.length; i++) {
				
				MAX = MAX.shiftLeft(8).add( BigInteger.valueOf(255) );
			}

			double y = number.doubleValue() / MAX.doubleValue();
			
			Float64Member value = G.DBL.construct();
			
			double MU = 255;
			
			double calculation = (Math.pow(1 + MU, y) - 1) / MU;
			
			if (negative) calculation = -calculation;
			
			value.setV(calculation);
			
			((DimensionedDataSource<Float64Member>) data).set(idx, value);
		}
		else {
			
			System.out.print("Unknown encoding in audio file: "+encoding);
			
			System.exit(1);
		}
	}
}
