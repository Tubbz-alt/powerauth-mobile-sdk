/**
 * Copyright 2018 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#import "PA2ObjectSerialization.h"
#import "PA2Request.h"
#import "PA2EncryptedRequest.h"
#import "PA2EncryptedResponse.h"
#import "PA2ECIESEncryptor.h"
#import "PA2PrivateMacros.h"
#import "PA2Log.h"

#pragma mark - De / Serialization -

@implementation PA2ObjectSerialization

+ (NSData*) serializeObject:(id<PA2Encodable>)object
{
	if (object) {
		NSData * data = [NSJSONSerialization dataWithJSONObject:[object toDictionary] options:0 error:nil];
		if (!data) {
			PA2Log(@"PA2ObjectSerialization: Failed to serialize object %@", object);
		}
		return data;
	}
	return [NSData dataWithBytes:"{}" length:2];
}

+ (id<PA2Decodable>) deserializeObject:(NSData*)data forClass:(Class)aClass error:(NSError**)error
{
	NSError * localError = nil;
	id JSONData = [NSJSONSerialization JSONObjectWithData:data options:0 error:error];
	if (JSONData) {
		NSDictionary * dictionary = PA2ObjectAs(JSONData, NSDictionary);
		if (dictionary) {
			id<PA2Decodable> object = [[aClass alloc] initWithDictionary:dictionary];
			if (object) {
				// Success
				return object;
			}
			localError = PA2MakeError(PA2ErrorCodeNetworkError, @"Wrong object received in response.");
		} else {
			localError = PA2MakeError(PA2ErrorCodeNetworkError, @"Unexpected type of JSON data.");
		}
	} else if (!localError) {
		// Fallback, in case that no error was produced. It's important to always
		// return some error from this function, because several response processing routines
		// depends on this.
		localError = PA2MakeError(PA2ErrorCodeNetworkError, @"Cannot deserialize JSON data.");
	}
	if (error) {
		*error = localError;
	}
	return nil;
}

@end



#pragma mark - Request / Response -

@implementation PA2ObjectSerialization (RequestResponse)

+ (NSData*) serializeRequestObject:(id<PA2Encodable>)object
{
	if (object) {
		return [self serializeObject:[[PA2Request alloc] initWithObject:object]];
	}
	return [NSData dataWithBytes:"{}" length:2];
}

+ (PA2Response*) deserializeResponseObject:(NSData*)data forClass:(Class)aClass error:(NSError**)error;
{
	NSError * localError = nil;
	id JSONData = [NSJSONSerialization JSONObjectWithData:data options:0 error:&localError];
	if (JSONData) {
		NSDictionary * dictionary = PA2ObjectAs(JSONData, NSDictionary);
		if (dictionary) {
			// Response can be always created. If there's no "status" value in dictionary,
			// then the object ends with "error" status.
			return [[PA2Response alloc] initWithDictionary:dictionary responseObjectType:aClass];
		} else {
			localError = PA2MakeError(PA2ErrorCodeNetworkError, @"Unexpected type of JSON data.");
		}
	} else if (!localError) {
		// Fallback, in case that no error was produced. It's important to always
		// return some error from this function, because several response processing routines
		// depends on this.
		localError = PA2MakeError(PA2ErrorCodeNetworkError, @"Cannot deserialize JSON data.");
	}
	if (error) {
		*error = localError;
	}
	return nil;
}

@end



#pragma mark - E2EE -

@implementation PA2ObjectSerialization (E2EE)

+ (NSData*) encryptObject:(id<PA2Encodable>)object
				encryptor:(PA2ECIESEncryptor*)encryptor
					error:(NSError**)error
{
	// Serialize object
	NSData * data = [self serializeObject:object];
	// Encrypt data
	PA2ECIESCryptogram * cryptogram = [encryptor encryptRequest:data];
	if (!cryptogram) {
		if (error) *error = PA2MakeError(PA2ErrorCodeEncryption, @"Failed to encrypt object data.");
		return nil;
	}
	// Finally, construct a request body from cryptogram
	PA2EncryptedRequest * requestObject = [[PA2EncryptedRequest alloc] initWithCryptogram:cryptogram];
	return [PA2ObjectSerialization serializeObject:requestObject];
}


+ (id<PA2Decodable>) decryptObject:(NSData*)data
						  forClass:(Class)aClass
						 decryptor:(PA2ECIESEncryptor*)decryptor
							 error:(NSError**)error
{
	NSData * decryptedData = [self decryptData:data decryptor:decryptor error:error];
	if (!decryptedData) {
		return nil;
	}
	
	// Handle unspecified response object
	if (!aClass) {
		// If response class is not specified, just return nil.
		if (error) *error = nil;
		return nil;
	}
	
	// Now try to deserialize response
	return [self deserializeObject:decryptedData forClass:aClass error:error];
}


+ (NSData*) decryptData:(NSData*)data
			  decryptor:(PA2ECIESEncryptor*)decryptor
				  error:(NSError**)error
{
	// Deserialize data to PA2EncryptedResponse
	PA2EncryptedResponse * encryptedResponse = [self deserializeObject:data
															  forClass:[PA2EncryptedResponse class]
																 error:error];
	if (!encryptedResponse) {
		return nil;
	}
	// Decrypt data
	NSData * decryptedData = [decryptor decryptResponse:[encryptedResponse cryptogram]];
	if (!decryptedData) {
		if (error) *error = PA2MakeError(PA2ErrorCodeEncryption, @"Failed to decrypt object data.");
		return nil;
	}
	return decryptedData;
}

@end

